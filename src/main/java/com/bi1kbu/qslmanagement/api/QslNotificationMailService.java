package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

@Service
public class QslNotificationMailService {

    private static final String MAIL_STATUS_SENT = "SENT";
    private static final String MAIL_STATUS_FAILED = "FAILED";
    private static final String MAIL_STATUS_SKIPPED = "SKIPPED";
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String STATION_PROFILE_NAME = "qsl-station-profile-default";
    private static final String QSL_API_VERSION = "qsl-management.bi1kbu.com/v1alpha1";
    private static final String CARD_RECORD_KIND = "CardRecord";
    private static final String EXCHANGE_REQUEST_KIND = "ExchangeRequest";
    private static final String EXCHANGE_REVIEW_REASON_TYPE = "qsl-exchange-reviewed";
    private static final String ONLINE_AUTO_APPROVED_REQUEST_REASON_TYPE = "qsl-online-auto-approved-request";
    private static final String MAIL_POLICY_AUTO_SKIP = "AUTO_SKIP";
    private static final String MAIL_POLICY_MANUAL = "MANUAL";
    private static final String MAIL_POLICY_AUTO_SEND = "AUTO_SEND";

    private final ReactiveExtensionClient client;
    private final NotificationCenter notificationCenter;
    private final NotificationReasonEmitter notificationReasonEmitter;
    private final QslAuditService qslAuditService;

    public QslNotificationMailService(
        ReactiveExtensionClient client,
        NotificationCenter notificationCenter,
        NotificationReasonEmitter notificationReasonEmitter,
        QslAuditService qslAuditService
    ) {
        this.client = client;
        this.notificationCenter = notificationCenter;
        this.notificationReasonEmitter = notificationReasonEmitter;
        this.qslAuditService = qslAuditService;
    }

    public Mono<NotificationMailSendResult> sendSingle(
        String cardRecordName,
        String sceneCode,
        String operator,
        String clientIp,
        String source
    ) {
        if (StringUtils.isBlank(cardRecordName)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片记录名称不能为空"));
        }

        var scene = MailScene.fromCode(sceneCode);
        if (scene == null) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮件场景不支持"));
        }

        return fetchOr404(CardRecord.class, cardRecordName.trim())
            .flatMap(cardRecord -> sendSceneMail(cardRecord, scene, operator, clientIp, source));
    }

    public Mono<NotificationMailBatchSendResult> sendBatch(
        List<String> cardRecordNames,
        String sceneCode,
        String operator,
        String clientIp,
        String source
    ) {
        var scene = MailScene.fromCode(sceneCode);
        if (scene == null) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮件场景不支持"));
        }

        if (cardRecordNames == null || cardRecordNames.isEmpty()) {
            return Mono.just(new NotificationMailBatchSendResult(
                scene.code,
                0,
                0,
                0,
                0,
                List.of()
            ));
        }

        Set<String> normalizedNames = new LinkedHashSet<>();
        cardRecordNames.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(StringUtils::isNotBlank)
            .forEach(normalizedNames::add);

        return Flux.fromIterable(normalizedNames)
            .concatMap(name -> sendSingle(name, scene.code, operator, clientIp, source)
                .onErrorResume(error -> Mono.just(new NotificationMailSendResult(
                    name,
                    scene.code,
                    MAIL_STATUS_FAILED,
                    "发送失败：" + shortError(error),
                    "",
                    ""
                ))))
            .collectList()
            .map(results -> {
                int sentCount = (int) results.stream().filter(item -> MAIL_STATUS_SENT.equals(item.status())).count();
                int skippedCount = (int) results.stream()
                    .filter(item -> MAIL_STATUS_SKIPPED.equals(item.status()))
                    .count();
                int failedCount = (int) results.stream().filter(item -> MAIL_STATUS_FAILED.equals(item.status())).count();
                return new NotificationMailBatchSendResult(
                    scene.code,
                    results.size(),
                    sentCount,
                    skippedCount,
                    failedCount,
                    results
                );
            });
    }

    public Mono<Void> autoSendIfEnabled(
        String cardRecordName,
        MailScene scene,
        String operator,
        String clientIp
    ) {
        return fetchOr404(CardRecord.class, cardRecordName)
            .flatMap(cardRecord -> loadSystemSetting()
                .flatMap(systemSetting -> {
                    var spec = ensureCardRecordSpec(cardRecord);
                    if (!scene.isAutoEnabled(systemSetting, spec)) {
                        return Mono.empty();
                    }
                    return sendSceneMail(cardRecord, scene, operator, clientIp, "自动触发")
                        .then();
                }))
            .onErrorResume(error -> Mono.empty());
    }

    public Mono<Void> autoSendExchangeReviewIfEnabled(
        String requestName,
        String operator,
        String clientIp
    ) {
        return loadSystemSetting()
            .flatMap(systemSetting -> {
                if (!isMailPolicyAutoSend(systemSetting.getOnlineExchangeReviewedMailPolicy(),
                    systemSetting.getOnlineAutoNotifyOnExchangeReviewed(),
                    systemSetting.getAutoNotifyOnExchangeReviewed())) {
                    return Mono.empty();
                }
                return sendExchangeReviewMail(requestName, operator, clientIp, "自动触发")
                    .then();
            })
            .onErrorResume(error -> Mono.empty());
    }

    public Mono<Void> autoSendOnlineAutoApprovedRequestIfEnabled(
        String requestName,
        String operator,
        String clientIp
    ) {
        return loadSystemSetting()
            .flatMap(systemSetting -> {
                var policy = StringUtils.defaultString(systemSetting.getOnlineAutoApprovedRequestMailPolicy())
                    .trim()
                    .toUpperCase(Locale.ROOT);
                if (!MAIL_POLICY_AUTO_SEND.equals(policy)) {
                    return Mono.empty();
                }
                return sendOnlineAutoApprovedRequestMail(requestName, operator, clientIp).then();
            })
            .onErrorResume(error -> Mono.empty());
    }

    public Mono<NotificationMailSendResult> sendTestMail(
        String sceneCode,
        String operator,
        String clientIp
    ) {
        var scene = TestMailScene.fromCode(sceneCode);
        if (scene == null) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮件测试场景不支持"));
        }

        return loadStationProfile()
            .flatMap(stationProfile -> {
                var targetEmail = StringUtils.defaultString(stationProfile.getMyEmail()).trim();
                if (StringUtils.isBlank(targetEmail)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "本台电子邮件为空，不能发送测试邮件"));
                }

                var sentAt = QslApiSupport.nowText();
                return subscribeTarget(targetEmail, scene.reasonType, scene.subjectKind)
                    .then(emitTestReason(scene, stationProfile, targetEmail, safeOperator(operator), sentAt))
                    .then(qslAuditService.appendAuditLog(
                        "发送测试邮件",
                        "system-setting",
                        SYSTEM_SETTING_NAME,
                        "场景=" + scene.code + "；目标邮箱=" + targetEmail + "；卡片类型=EYEBALL；卡片编号=C0001",
                        safeOperator(operator),
                        clientIp
                    ))
                    .thenReturn(new NotificationMailSendResult(
                        "C0001",
                        scene.code,
                        MAIL_STATUS_SENT,
                        "测试邮件已提交给系统邮件通道。",
                        targetEmail,
                        sentAt
                    ))
                    .onErrorResume(error -> Mono.just(new NotificationMailSendResult(
                        "C0001",
                        scene.code,
                        MAIL_STATUS_FAILED,
                        "测试邮件发送失败：" + shortError(error),
                        targetEmail,
                        ""
                    )));
            });
    }

    public Mono<ExchangeReviewMailSendResult> sendExchangeReviewMail(
        String requestName,
        String operator,
        String clientIp,
        String source
    ) {
        if (StringUtils.isBlank(requestName)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "换卡申请名称不能为空"));
        }

        return fetchOr404(ExchangeRequest.class, requestName.trim())
            .flatMap(exchangeRequest -> {
                var spec = exchangeRequest.getSpec();
                var status = ensureExchangeRequestStatus(exchangeRequest);
                var reviewStatus = StringUtils.defaultString(status.getReviewStatus()).trim();
                if (StringUtils.isBlank(reviewStatus) || "待审核".equals(reviewStatus)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "换卡申请尚未审核，不能发送审核结果通知"));
                }

                if (MAIL_STATUS_SENT.equalsIgnoreCase(StringUtils.defaultString(status.getReviewMailStatus()))) {
                    return Mono.just(new ExchangeReviewMailSendResult(
                        exchangeRequest.getMetadata().getName(),
                        MAIL_STATUS_SKIPPED,
                        "审核通知邮件已发送，已跳过。",
                        StringUtils.defaultString(status.getReviewMailTargetEmail()),
                        StringUtils.defaultString(status.getReviewMailSentAt())
                    ));
                }

                var targetEmail = spec == null ? "" : StringUtils.defaultString(spec.getEmail()).trim();
                if (StringUtils.isBlank(targetEmail)) {
                    return persistExchangeReviewMailStatusAndAudit(
                        exchangeRequest,
                        MAIL_STATUS_SKIPPED,
                        "申请未配置电子邮箱，已跳过。",
                        "",
                        "",
                        operator,
                        clientIp,
                        source
                    );
                }

                var sentAt = QslApiSupport.nowText();
                return subscribeTarget(targetEmail, EXCHANGE_REVIEW_REASON_TYPE, EXCHANGE_REQUEST_KIND)
                    .then(emitExchangeReviewReason(exchangeRequest, targetEmail, safeOperator(operator), sentAt))
                    .then(persistExchangeReviewMailStatusAndAudit(
                        exchangeRequest,
                        MAIL_STATUS_SENT,
                        "发送成功。",
                        targetEmail,
                        sentAt,
                        operator,
                        clientIp,
                        source
                    ))
                    .onErrorResume(error -> persistExchangeReviewMailStatusAndAudit(
                        exchangeRequest,
                        MAIL_STATUS_FAILED,
                        "发送失败：" + shortError(error),
                        targetEmail,
                        "",
                        operator,
                        clientIp,
                        source
                    ));
            });
    }

    private Mono<Void> sendOnlineAutoApprovedRequestMail(
        String requestName,
        String operator,
        String clientIp
    ) {
        return fetchOr404(ExchangeRequest.class, requestName.trim())
            .flatMap(exchangeRequest -> loadStationProfile()
                .flatMap(stationProfile -> {
                    var targetEmail = StringUtils.defaultString(stationProfile.getMyEmail()).trim();
                    if (StringUtils.isBlank(targetEmail)) {
                        return Mono.empty();
                    }
                    var sentAt = QslApiSupport.nowText();
                    return subscribeTarget(targetEmail, ONLINE_AUTO_APPROVED_REQUEST_REASON_TYPE, EXCHANGE_REQUEST_KIND)
                        .then(emitOnlineAutoApprovedRequestReason(exchangeRequest, stationProfile, targetEmail,
                            safeOperator(operator), sentAt))
                        .then(qslAuditService.appendAuditLog(
                            "自动审批线上换卡申请邮件通知",
                            "exchange-request",
                            exchangeRequest.getMetadata().getName(),
                            "目标邮箱=" + targetEmail + "；状态=SENT；消息=自动审批通过后已通知本台。",
                            safeOperator(operator),
                            clientIp
                        ));
                }));
    }

    private Mono<NotificationMailSendResult> sendSceneMail(
        CardRecord cardRecord,
        MailScene scene,
        String operator,
        String clientIp,
        String source
    ) {
        var spec = ensureCardRecordSpec(cardRecord);
        var cardRecordName = cardRecord.getMetadata().getName();

        if (scene.currentStatus(spec).equalsIgnoreCase(MAIL_STATUS_SENT)) {
            return Mono.just(new NotificationMailSendResult(
                cardRecordName,
                scene.code,
                MAIL_STATUS_SKIPPED,
                "该场景邮件已发送，已跳过。",
                StringUtils.defaultString(spec.getMailTargetEmail()),
                StringUtils.defaultString(scene.currentSentAt(spec))
            ));
        }

        if (!scene.meetsBusinessState(spec)) {
            return persistStatusAndAudit(
                cardRecord,
                spec,
                scene,
                MAIL_STATUS_SKIPPED,
                "",
                "",
                "当前记录尚未满足该场景发送条件。",
                operator,
                clientIp,
                source
            );
        }

        var boundAddressEntryName = StringUtils.defaultString(spec.getAddressEntryName()).trim();
        if (StringUtils.isBlank(boundAddressEntryName)) {
            return persistStatusAndAudit(
                cardRecord,
                spec,
                scene,
                MAIL_STATUS_SENT,
                QslApiSupport.nowText(),
                "",
                "卡片记录未绑定地址，按不发邮件处理。",
                operator,
                clientIp,
                source
            );
        }

        return resolveTargetEmailByBindingAddress(boundAddressEntryName)
            .flatMap(targetEmail -> {
                if (StringUtils.isBlank(targetEmail)) {
                    return persistStatusAndAudit(
                        cardRecord,
                        spec,
                        scene,
                        MAIL_STATUS_SENT,
                        QslApiSupport.nowText(),
                        "",
                        "绑定地址未配置可用邮箱，按不发邮件处理。",
                        operator,
                        clientIp,
                        source
                    );
                }

                var sentAt = QslApiSupport.nowText();
                return subscribeTarget(targetEmail, scene)
                    .then(emitReason(cardRecordName, spec, scene, targetEmail, operator, sentAt))
                    .then(persistStatusAndAudit(
                        cardRecord,
                        spec,
                        scene,
                        MAIL_STATUS_SENT,
                        sentAt,
                        targetEmail,
                        "发送成功。",
                        operator,
                        clientIp,
                        source
                    ))
                    .onErrorResume(error -> persistStatusAndAudit(
                        cardRecord,
                        spec,
                        scene,
                        MAIL_STATUS_FAILED,
                        "",
                        targetEmail,
                        "发送失败：" + shortError(error),
                        operator,
                        clientIp,
                        source
                    ));
            });
    }

    private Mono<NotificationMailSendResult> persistStatusAndAudit(
        CardRecord cardRecord,
        CardRecord.CardRecordSpec spec,
        MailScene scene,
        String status,
        String sentAt,
        String targetEmail,
        String message,
        String operator,
        String clientIp,
        String source
    ) {
        scene.updateStatus(spec, status);
        scene.updateSentAt(spec, sentAt);
        scene.updateLastError(spec, MAIL_STATUS_FAILED.equals(status) ? message : "");
        if (StringUtils.isNotBlank(targetEmail)) {
            spec.setMailTargetEmail(targetEmail);
        }
        cardRecord.setSpec(spec);

        var safeSource = StringUtils.isBlank(source) ? "手动触发" : source.trim();
        var action = scene.displayName + "邮件通知";
        var detail = "来源=" + safeSource
            + "；状态=" + status
            + "；目标邮箱=" + (StringUtils.isBlank(targetEmail) ? "未匹配" : targetEmail)
            + "；消息=" + message;

        return client.update(cardRecord)
            .flatMap(updated -> qslAuditService.appendAuditLog(
                action,
                "card-record",
                updated.getMetadata().getName(),
                detail,
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .map(updated -> new NotificationMailSendResult(
                updated.getMetadata().getName(),
                scene.code,
                status,
                message,
                StringUtils.defaultString(spec.getMailTargetEmail()),
                StringUtils.defaultString(scene.currentSentAt(spec))
            ));
    }

    private Mono<Void> emitReason(
        String cardRecordName,
        CardRecord.CardRecordSpec spec,
        MailScene scene,
        String targetEmail,
        String operator,
        String sentAt
    ) {
        return loadStationProfile()
            .flatMap(stationProfile -> {
                var anonymousIdentity = UserIdentity.anonymousWithEmail(targetEmail).name();
                var normalizedCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
                var subject = Reason.Subject.builder()
                    .apiVersion(QSL_API_VERSION)
                    .kind(CARD_RECORD_KIND)
                    .name(anonymousIdentity)
                    .title(scene.displayName + "：" + normalizedCallSign)
                    .build();

                return notificationReasonEmitter.emit(scene.reasonType, builder -> builder
                    .subject(subject)
                    .author(UserIdentity.of(safeOperator(operator)))
                    .attribute("sceneDisplay", scene.displayName)
                    .attribute("stationCallSign", stationCallSign(stationProfile))
                    .attribute("callSign", normalizedCallSign)
                    .attribute("cardType", StringUtils.defaultString(spec.getCardType()))
                    .attribute("cardVersion", StringUtils.defaultString(spec.getCardVersion()))
                    .attribute("cardDate", StringUtils.defaultString(spec.getCardDate()))
                    .attribute("cardTime", StringUtils.defaultString(spec.getCardTime()))
                    .attribute("cardRecordName", cardRecordName)
                    .attribute("remarks", resolveSceneRemarks(scene, spec))
                    .attribute("targetEmail", targetEmail)
                    .attribute("triggerAt", sentAt)
                    .attribute("dateText", dateText(sentAt))
                    .attribute("operator", safeOperator(operator))
                );
            });
    }

    private String resolveSceneRemarks(MailScene scene, CardRecord.CardRecordSpec spec) {
        return switch (scene) {
            case CARD_CREATED -> StringUtils.defaultString(spec.getCardRemarks());
            case CARD_SENT -> StringUtils.defaultString(spec.getCardRemarks());
            case CARD_RECEIVED -> StringUtils.defaultString(spec.getReceivedRemarks());
        };
    }

    private Mono<Void> subscribeTarget(String targetEmail, MailScene scene) {
        return subscribeTarget(targetEmail, scene.reasonType, CARD_RECORD_KIND);
    }

    private Mono<Void> subscribeTarget(String targetEmail, String reasonType, String subjectKind) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(targetEmail).name());

        var reason = new Subscription.InterestReason();
        reason.setReasonType(reasonType);
        reason.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion(QSL_API_VERSION)
            .kind(subjectKind)
            .name(subscriber.getName())
            .build());

        return notificationCenter.subscribe(subscriber, reason).then();
    }

    private Mono<Void> emitExchangeReviewReason(
        ExchangeRequest exchangeRequest,
        String targetEmail,
        String operator,
        String sentAt
    ) {
        var spec = exchangeRequest.getSpec() == null
            ? new ExchangeRequest.ExchangeRequestSpec()
            : exchangeRequest.getSpec();
        var status = exchangeRequest.getStatus() == null
            ? new ExchangeRequest.ExchangeRequestStatus()
            : exchangeRequest.getStatus();
        return loadStationProfile()
            .flatMap(stationProfile -> {
                var anonymousIdentity = UserIdentity.anonymousWithEmail(targetEmail).name();
                var callSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
                var reviewStatus = StringUtils.defaultString(status.getReviewStatus());
                var subject = Reason.Subject.builder()
                    .apiVersion(QSL_API_VERSION)
                    .kind(EXCHANGE_REQUEST_KIND)
                    .name(anonymousIdentity)
                    .title("QSL 换卡申请审核：" + callSign + " " + reviewStatus)
                    .build();

                return notificationReasonEmitter.emit(EXCHANGE_REVIEW_REASON_TYPE, builder -> builder
                    .subject(subject)
                    .author(UserIdentity.of(operator))
                    .attribute("stationCallSign", stationCallSign(stationProfile))
                    .attribute("stationName", StringUtils.defaultString(stationProfile.getMyName()))
                    .attribute("stationTelephone", StringUtils.defaultString(stationProfile.getMyTelephone()))
                    .attribute("stationPostalCode", StringUtils.defaultString(stationProfile.getMyPostalCode()))
                    .attribute("stationAddress", StringUtils.defaultString(stationProfile.getMyAddress()))
                    .attribute("stationEmail", StringUtils.defaultString(stationProfile.getMyEmail()))
                    .attribute("callSign", callSign)
                    .attribute("targetName", StringUtils.defaultString(spec.getName()))
                    .attribute("targetTelephone", StringUtils.defaultString(spec.getTelephone()))
                    .attribute("reviewStatus", reviewStatus)
                    .attribute("reviewResultText", exchangeReviewResultText(reviewStatus))
                    .attribute("reviewMailIntro", exchangeReviewIntro(reviewStatus, stationCallSign(stationProfile)))
                    .attribute("reviewReason", StringUtils.defaultString(status.getReviewReason()))
                    .attribute("requestName", StringUtils.defaultString(exchangeRequest.getMetadata().getName()))
                    .attribute("cardVersion", StringUtils.defaultString(spec.getCardVersion()))
                    .attribute("addressMode", Boolean.TRUE.equals(spec.getUseBureau()) ? "卡片局地址" : "个人地址")
                    .attribute("bureauName", StringUtils.defaultString(spec.getBureauName()))
                    .attribute("postalCode", StringUtils.defaultString(spec.getPostalCode()))
                    .attribute("address", StringUtils.defaultString(spec.getAddress()))
                    .attribute("remarks", StringUtils.defaultString(spec.getRemarks()))
                    .attribute("targetEmail", targetEmail)
                    .attribute("triggerAt", sentAt)
                    .attribute("dateText", dateText(sentAt))
                    .attribute("operator", operator)
                ).then();
            });
    }

    private Mono<Void> emitOnlineAutoApprovedRequestReason(
        ExchangeRequest exchangeRequest,
        StationProfile.StationProfileSpec stationProfile,
        String targetEmail,
        String operator,
        String sentAt
    ) {
        var spec = exchangeRequest.getSpec() == null
            ? new ExchangeRequest.ExchangeRequestSpec()
            : exchangeRequest.getSpec();
        var status = exchangeRequest.getStatus() == null
            ? new ExchangeRequest.ExchangeRequestStatus()
            : exchangeRequest.getStatus();
        var anonymousIdentity = UserIdentity.anonymousWithEmail(targetEmail).name();
        var callSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
        var subject = Reason.Subject.builder()
            .apiVersion(QSL_API_VERSION)
            .kind(EXCHANGE_REQUEST_KIND)
            .name(anonymousIdentity)
            .title("QSL 自动审批线上换卡申请：" + callSign)
            .build();

        return notificationReasonEmitter.emit(ONLINE_AUTO_APPROVED_REQUEST_REASON_TYPE, builder -> builder
            .subject(subject)
            .author(UserIdentity.of(operator))
            .attribute("stationCallSign", stationCallSign(stationProfile))
            .attribute("stationName", StringUtils.defaultString(stationProfile.getMyName()))
            .attribute("stationEmail", StringUtils.defaultString(stationProfile.getMyEmail()))
            .attribute("callSign", callSign)
            .attribute("targetName", StringUtils.defaultString(spec.getName()))
            .attribute("targetTelephone", StringUtils.defaultString(spec.getTelephone()))
            .attribute("targetEmail", StringUtils.defaultString(spec.getEmail()))
            .attribute("requestName", StringUtils.defaultString(exchangeRequest.getMetadata().getName()))
            .attribute("cardVersion", StringUtils.defaultString(spec.getCardVersion()))
            .attribute("addressMode", Boolean.TRUE.equals(spec.getUseBureau()) ? "卡片局地址" : "个人地址")
            .attribute("bureauName", StringUtils.defaultString(spec.getBureauName()))
            .attribute("postalCode", StringUtils.defaultString(spec.getPostalCode()))
            .attribute("address", StringUtils.defaultString(spec.getAddress()))
            .attribute("remarks", StringUtils.defaultString(spec.getRemarks()))
            .attribute("reviewStatus", StringUtils.defaultString(status.getReviewStatus()))
            .attribute("reviewReason", StringUtils.defaultString(status.getReviewReason()))
            .attribute("triggerAt", sentAt)
            .attribute("dateText", dateText(sentAt))
            .attribute("operator", operator)
        ).then();
    }

    private Mono<Void> emitTestReason(
        TestMailScene scene,
        StationProfile.StationProfileSpec stationProfile,
        String targetEmail,
        String operator,
        String sentAt
    ) {
        var anonymousIdentity = UserIdentity.anonymousWithEmail(targetEmail).name();
        var callSign = stationCallSign(stationProfile);
        var subject = Reason.Subject.builder()
            .apiVersion(QSL_API_VERSION)
            .kind(scene.subjectKind)
            .name(anonymousIdentity)
            .title("QSL 测试邮件：" + scene.displayName)
            .build();

        return notificationReasonEmitter.emit(scene.reasonType, builder -> builder
            .subject(subject)
            .author(UserIdentity.of(operator))
            .attribute("sceneDisplay", scene.displayName)
            .attribute("stationCallSign", callSign)
            .attribute("stationName", StringUtils.defaultString(stationProfile.getMyName()))
            .attribute("stationTelephone", StringUtils.defaultString(stationProfile.getMyTelephone()))
            .attribute("stationPostalCode", StringUtils.defaultString(stationProfile.getMyPostalCode()))
            .attribute("stationAddress", StringUtils.defaultString(stationProfile.getMyAddress()))
            .attribute("stationEmail", StringUtils.defaultString(stationProfile.getMyEmail()))
            .attribute("callSign", callSign)
            .attribute("targetName", StringUtils.defaultIfBlank(stationProfile.getMyName(), callSign))
            .attribute("targetTelephone", StringUtils.defaultString(stationProfile.getMyTelephone()))
            .attribute("cardType", "EYEBALL")
            .attribute("cardVersion", "")
            .attribute("cardDate", dateText(sentAt))
            .attribute("cardTime", sentAt)
            .attribute("cardRecordName", "C0001")
            .attribute("reviewStatus", "已通过")
            .attribute("reviewResultText", "已被通过")
            .attribute("reviewMailIntro", exchangeReviewIntro("已通过", callSign))
            .attribute("reviewReason", "测试邮件")
            .attribute("requestName", "C0001")
            .attribute("addressMode", "个人地址")
            .attribute("bureauName", "")
            .attribute("postalCode", StringUtils.defaultString(stationProfile.getMyPostalCode()))
            .attribute("address", StringUtils.defaultString(stationProfile.getMyAddress()))
            .attribute("remarks", "测试邮件")
            .attribute("targetEmail", targetEmail)
            .attribute("triggerAt", sentAt)
            .attribute("dateText", dateText(sentAt))
            .attribute("operator", operator)
        ).then();
    }

    private Mono<ExchangeReviewMailSendResult> persistExchangeReviewMailStatusAndAudit(
        ExchangeRequest exchangeRequest,
        String status,
        String message,
        String targetEmail,
        String sentAt,
        String operator,
        String clientIp,
        String source
    ) {
        var requestStatus = ensureExchangeRequestStatus(exchangeRequest);
        requestStatus.setReviewMailStatus(status);
        requestStatus.setReviewMailSentAt(MAIL_STATUS_SENT.equals(status) ? sentAt : "");
        requestStatus.setReviewMailLastError(MAIL_STATUS_FAILED.equals(status) ? message : "");
        if (StringUtils.isNotBlank(targetEmail)) {
            requestStatus.setReviewMailTargetEmail(targetEmail);
        }
        exchangeRequest.setStatus(requestStatus);

        var requestName = exchangeRequest.getMetadata().getName();
        var safeSource = StringUtils.isBlank(source) ? "手动触发" : source.trim();
        var detail = "来源=" + safeSource
            + "；状态=" + status
            + "；目标邮箱=" + (StringUtils.isBlank(targetEmail) ? "未配置" : targetEmail)
            + "；消息=" + message;
        return client.update(exchangeRequest)
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "换卡审核邮件通知",
                "exchange-request",
                requestName,
                detail,
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .map(updated -> {
                var updatedStatus = ensureExchangeRequestStatus(updated);
                return new ExchangeReviewMailSendResult(
                    requestName,
                    status,
                    message,
                    StringUtils.defaultString(updatedStatus.getReviewMailTargetEmail()),
                    StringUtils.defaultString(updatedStatus.getReviewMailSentAt())
                );
            });
    }

    private Mono<String> resolveTargetEmailByBindingAddress(String addressEntryName) {
        return client.fetch(AddressBookEntry.class, addressEntryName)
            .map(entry -> entry.getSpec() == null ? "" : StringUtils.defaultString(entry.getSpec().getEmail()).trim())
            .filter(StringUtils::isNotBlank)
            .switchIfEmpty(Mono.just(""));
    }

    private Mono<SystemSetting.SystemSettingSpec> loadSystemSetting() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(SystemSetting::getSpec)
            .map(spec -> spec == null ? new SystemSetting.SystemSettingSpec() : spec)
            .switchIfEmpty(Mono.just(new SystemSetting.SystemSettingSpec()));
    }

    private Mono<StationProfile.StationProfileSpec> loadStationProfile() {
        return client.fetch(StationProfile.class, STATION_PROFILE_NAME)
            .map(StationProfile::getSpec)
            .map(spec -> spec == null ? new StationProfile.StationProfileSpec() : spec)
            .switchIfEmpty(Mono.just(new StationProfile.StationProfileSpec()));
    }

    private CardRecord.CardRecordSpec ensureCardRecordSpec(CardRecord cardRecord) {
        if (cardRecord.getSpec() != null) {
            return cardRecord.getSpec();
        }
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign("");
        spec.setCardType("QSO");
        spec.setSceneType("QSO");
        spec.setCardVersion("");
        spec.setQsoRecordName("");
        spec.setOfflineActivityName("");
        spec.setAddressEntryName("");
        spec.setCardDate("");
        spec.setCardTime("");
        spec.setBusinessRemarks("");
        spec.setCreatedRemarks("");
        spec.setSentRemarks("");
        spec.setReceivedRemarks("");
        spec.setPublicReceiptRemarks("");
        spec.setCardRemarks("");
        spec.setCardSent(Boolean.FALSE);
        spec.setCardIssued(Boolean.FALSE);
        spec.setEnvelopePrinted(Boolean.FALSE);
        spec.setCardReceived(Boolean.FALSE);
        spec.setReceiptConfirmed(Boolean.FALSE);
        spec.setCardIssuedAt("");
        spec.setSentAt("");
        spec.setReceivedAt("");
        spec.setCreatedMailStatus("");
        spec.setCreatedMailSentAt("");
        spec.setCreatedMailLastError("");
        spec.setSentMailStatus("");
        spec.setSentMailSentAt("");
        spec.setSentMailLastError("");
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
        spec.setMailTargetEmail("");
        cardRecord.setSpec(spec);
        return spec;
    }

    private ExchangeRequest.ExchangeRequestStatus ensureExchangeRequestStatus(ExchangeRequest exchangeRequest) {
        if (exchangeRequest.getStatus() != null) {
            return exchangeRequest.getStatus();
        }
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        status.setReviewReason("");
        status.setReviewedBy("");
        status.setReviewedAt("");
        status.setReviewMailStatus("");
        status.setReviewMailSentAt("");
        status.setReviewMailLastError("");
        status.setReviewMailTargetEmail("");
        exchangeRequest.setStatus(status);
        return status;
    }

    private String safeOperator(String operator) {
        if (StringUtils.isBlank(operator)) {
            return "qsl-system";
        }
        return operator.trim();
    }

    private String shortError(Throwable error) {
        if (error == null || error.getMessage() == null) {
            return "未知错误";
        }
        var message = error.getMessage().trim();
        if (message.length() > 120) {
            return message.substring(0, 120);
        }
        return message;
    }

    private String stationCallSign(StationProfile.StationProfileSpec stationProfile) {
        return QslApiSupport.normalizeCallSign(StringUtils.defaultString(stationProfile.getMyCallSign()));
    }

    private String dateText(String sentAt) {
        var value = StringUtils.defaultString(sentAt).trim();
        if (value.length() >= 10) {
            return value.substring(0, 10);
        }
        return QslApiSupport.nowText().substring(0, 10);
    }

    private String exchangeReviewResultText(String reviewStatus) {
        return "已拒绝".equals(StringUtils.defaultString(reviewStatus).trim()) ? "已被拒绝" : "已被通过";
    }

    private String exchangeReviewIntro(String reviewStatus, String stationCallSign) {
        if ("已拒绝".equals(StringUtils.defaultString(reviewStatus).trim())) {
            return "您的线上交换眼球卡片申请已被拒绝，请查看审核说明。";
        }
        return "很高兴与您线上交换眼球卡片，" + StringUtils.defaultString(stationCallSign) + "将尽快发出您的卡片。";
    }

    private <E extends run.halo.app.extension.Extension> Mono<E> fetchOr404(Class<E> extensionType, String name) {
        return client.fetch(extensionType, name)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "资源不存在")));
    }

    public enum MailScene {
        CARD_CREATED("created", "qsl-card-created", "制卡"),
        CARD_SENT("sent", "qsl-card-sent", "发卡"),
        CARD_RECEIVED("received", "qsl-card-received", "收卡");

        private final String code;
        private final String reasonType;
        private final String displayName;

        MailScene(String code, String reasonType, String displayName) {
            this.code = code;
            this.reasonType = reasonType;
            this.displayName = displayName;
        }

        static MailScene fromCode(String code) {
            if (StringUtils.isBlank(code)) {
                return null;
            }
            var normalized = code.trim().toLowerCase(Locale.ROOT);
            for (var scene : values()) {
                if (scene.code.equals(normalized)) {
                    return scene;
                }
            }
            return null;
        }

        boolean meetsBusinessState(CardRecord.CardRecordSpec spec) {
            return switch (this) {
                case CARD_CREATED -> true;
                case CARD_SENT -> Boolean.TRUE.equals(spec.getCardSent());
                case CARD_RECEIVED -> Boolean.TRUE.equals(spec.getCardReceived());
            };
        }

        String currentStatus(CardRecord.CardRecordSpec spec) {
            return switch (this) {
                case CARD_CREATED -> StringUtils.defaultString(spec.getCreatedMailStatus());
                case CARD_SENT -> StringUtils.defaultString(spec.getSentMailStatus());
                case CARD_RECEIVED -> StringUtils.defaultString(spec.getReceivedMailStatus());
            };
        }

        String currentSentAt(CardRecord.CardRecordSpec spec) {
            return switch (this) {
                case CARD_CREATED -> StringUtils.defaultString(spec.getCreatedMailSentAt());
                case CARD_SENT -> StringUtils.defaultString(spec.getSentMailSentAt());
                case CARD_RECEIVED -> StringUtils.defaultString(spec.getReceivedMailSentAt());
            };
        }

        void updateStatus(CardRecord.CardRecordSpec spec, String status) {
            switch (this) {
                case CARD_CREATED -> spec.setCreatedMailStatus(status);
                case CARD_SENT -> spec.setSentMailStatus(status);
                case CARD_RECEIVED -> spec.setReceivedMailStatus(status);
            }
        }

        void updateSentAt(CardRecord.CardRecordSpec spec, String sentAt) {
            switch (this) {
                case CARD_CREATED -> spec.setCreatedMailSentAt(sentAt);
                case CARD_SENT -> spec.setSentMailSentAt(sentAt);
                case CARD_RECEIVED -> spec.setReceivedMailSentAt(sentAt);
            }
        }

        void updateLastError(CardRecord.CardRecordSpec spec, String error) {
            switch (this) {
                case CARD_CREATED -> spec.setCreatedMailLastError(error);
                case CARD_SENT -> spec.setSentMailLastError(error);
                case CARD_RECEIVED -> spec.setReceivedMailLastError(error);
            }
        }

        boolean isAutoEnabled(SystemSetting.SystemSettingSpec systemSetting, CardRecord.CardRecordSpec spec) {
            var sceneType = normalizeSceneType(spec.getSceneType(), spec.getCardType());
            return switch (this) {
                case CARD_CREATED -> {
                    if ("ONLINE_EYEBALL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getOnlineCardCreatedMailPolicy(),
                            systemSetting.getOnlineAutoNotifyOnCardCreated(),
                            systemSetting.getAutoNotifyOnCardCreated());
                    }
                    if ("QSO".equals(sceneType) || "SWL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getQsoCardCreatedMailPolicy(),
                            systemSetting.getQsoAutoNotifyOnCardCreated(),
                            systemSetting.getAutoNotifyOnCardCreated());
                    }
                    yield false;
                }
                case CARD_SENT -> {
                    if ("ONLINE_EYEBALL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getOnlineCardSentMailPolicy(),
                            systemSetting.getOnlineAutoNotifyOnCardSent(),
                            systemSetting.getAutoNotifyOnCardSent());
                    }
                    if ("QSO".equals(sceneType) || "SWL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getQsoCardSentMailPolicy(),
                            systemSetting.getQsoAutoNotifyOnCardSent(),
                            systemSetting.getAutoNotifyOnCardSent());
                    }
                    yield false;
                }
                case CARD_RECEIVED -> {
                    if ("ONLINE_EYEBALL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getOnlineCardReceivedMailPolicy(),
                            systemSetting.getOnlineAutoNotifyOnCardReceived(),
                            systemSetting.getAutoNotifyOnCardReceived());
                    }
                    if ("EYEBALL".equals(sceneType)) {
                        yield false;
                    }
                    if ("QSO".equals(sceneType) || "SWL".equals(sceneType)) {
                        yield isMailPolicyAutoSend(systemSetting.getQsoCardReceivedMailPolicy(),
                            systemSetting.getQsoAutoNotifyOnCardReceived(),
                            systemSetting.getAutoNotifyOnCardReceived());
                    }
                    yield false;
                }
            };
        }

        private static String normalizeSceneType(String sceneType, String cardType) {
            var normalizedSceneType = StringUtils.defaultString(sceneType).trim().toUpperCase(Locale.ROOT);
            if (!normalizedSceneType.isBlank()) {
                return normalizedSceneType;
            }
            var normalizedCardType = StringUtils.defaultString(cardType).trim().toUpperCase(Locale.ROOT);
            if ("SWL".equals(normalizedCardType)) {
                return "SWL";
            }
            if ("EYEBALL".equals(normalizedCardType)) {
                return "EYEBALL";
            }
            return "QSO";
        }
    }

    private static boolean isSettingEnabled(Boolean sceneSetting, Boolean legacySetting) {
        return sceneSetting == null ? Boolean.TRUE.equals(legacySetting) : Boolean.TRUE.equals(sceneSetting);
    }

    private static boolean isMailPolicyAutoSend(String policy, Boolean sceneSetting, Boolean legacySetting) {
        var normalizedPolicy = StringUtils.defaultString(policy).trim().toUpperCase(Locale.ROOT);
        if (MAIL_POLICY_AUTO_SEND.equals(normalizedPolicy)) {
            return true;
        }
        if (MAIL_POLICY_AUTO_SKIP.equals(normalizedPolicy) || MAIL_POLICY_MANUAL.equals(normalizedPolicy)) {
            return false;
        }
        return isSettingEnabled(sceneSetting, legacySetting);
    }

    public enum TestMailScene {
        CARD_CREATED("created", "qsl-card-created", CARD_RECORD_KIND, "制卡"),
        CARD_SENT("sent", "qsl-card-sent", CARD_RECORD_KIND, "发卡"),
        CARD_RECEIVED("received", "qsl-card-received", CARD_RECORD_KIND, "收卡"),
        EXCHANGE_REVIEWED("exchange-reviewed", EXCHANGE_REVIEW_REASON_TYPE, EXCHANGE_REQUEST_KIND, "线上换卡审核");

        private final String code;
        private final String reasonType;
        private final String subjectKind;
        private final String displayName;

        TestMailScene(String code, String reasonType, String subjectKind, String displayName) {
            this.code = code;
            this.reasonType = reasonType;
            this.subjectKind = subjectKind;
            this.displayName = displayName;
        }

        static TestMailScene fromCode(String code) {
            if (StringUtils.isBlank(code)) {
                return null;
            }
            var normalized = code.trim().toLowerCase(Locale.ROOT);
            for (var scene : values()) {
                if (scene.code.equals(normalized)) {
                    return scene;
                }
            }
            return null;
        }
    }

    public record NotificationMailSendResult(
        String cardRecordName,
        String scene,
        String status,
        String message,
        String targetEmail,
        String sentAt
    ) {
    }

    public record NotificationMailBatchSendResult(
        String scene,
        int totalCount,
        int sentCount,
        int skippedCount,
        int failedCount,
        List<NotificationMailSendResult> results
    ) {
    }

    public record ExchangeReviewMailSendResult(
        String requestName,
        String status,
        String message,
        String targetEmail,
        String sentAt
    ) {
    }
}
