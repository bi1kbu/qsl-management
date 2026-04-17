package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.notification.Reason;
import run.halo.app.core.extension.notification.Subscription;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.notification.NotificationCenter;
import run.halo.app.notification.NotificationReasonEmitter;
import run.halo.app.notification.UserIdentity;

@Service
public class QslNotificationMailService {

    private static final String MAIL_STATUS_SENT = "SENT";
    private static final String MAIL_STATUS_FAILED = "FAILED";
    private static final String MAIL_STATUS_SKIPPED = "SKIPPED";
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String QSL_API_VERSION = "qsl-management.halo.run/v1alpha1";
    private static final String CARD_RECORD_KIND = "CardRecord";

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
        return loadSystemSetting()
            .flatMap(systemSetting -> {
                if (!scene.isAutoEnabled(systemSetting)) {
                    return Mono.empty();
                }
                return sendSingle(cardRecordName, scene.code, operator, clientIp, "自动触发")
                    .then();
            })
            .onErrorResume(error -> Mono.empty());
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

        var normalizedCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
        if (StringUtils.isBlank(normalizedCallSign)) {
            return persistStatusAndAudit(
                cardRecord,
                spec,
                scene,
                MAIL_STATUS_FAILED,
                "",
                "",
                "卡片记录缺少呼号，无法发送邮件。",
                operator,
                clientIp,
                source
            );
        }

        return resolveTargetEmail(normalizedCallSign)
            .flatMap(targetEmail -> {
                if (StringUtils.isBlank(targetEmail)) {
                    return persistStatusAndAudit(
                        cardRecord,
                        spec,
                        scene,
                        MAIL_STATUS_FAILED,
                        "",
                        "",
                        "地址管理中未找到可用邮箱。",
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
            .attribute("callSign", normalizedCallSign)
            .attribute("cardType", StringUtils.defaultString(spec.getCardType()))
            .attribute("cardVersion", StringUtils.defaultString(spec.getCardVersion()))
            .attribute("cardDate", StringUtils.defaultString(spec.getCardDate()))
            .attribute("cardTime", StringUtils.defaultString(spec.getCardTime()))
            .attribute("cardRecordName", cardRecordName)
            .attribute("remarks", StringUtils.defaultString(spec.getCardRemarks()))
            .attribute("targetEmail", targetEmail)
            .attribute("triggerAt", sentAt)
            .attribute("operator", safeOperator(operator))
        );
    }

    private Mono<Void> subscribeTarget(String targetEmail, MailScene scene) {
        var subscriber = new Subscription.Subscriber();
        subscriber.setName(UserIdentity.anonymousWithEmail(targetEmail).name());

        var reason = new Subscription.InterestReason();
        reason.setReasonType(scene.reasonType);
        reason.setSubject(Subscription.ReasonSubject.builder()
            .apiVersion(QSL_API_VERSION)
            .kind(CARD_RECORD_KIND)
            .name(subscriber.getName())
            .build());

        return notificationCenter.subscribe(subscriber, reason).then();
    }

    private Mono<String> resolveTargetEmail(String callSign) {
        return client.listAll(AddressBookEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(item -> item.getSpec() != null)
            .filter(item -> QslApiSupport.normalizeCallSign(item.getSpec().getCallSign()).equals(callSign))
            .map(item -> item.getSpec().getEmail())
            .map(email -> email == null ? "" : email.trim())
            .filter(StringUtils::isNotBlank)
            .next()
            .defaultIfEmpty("");
    }

    private Mono<SystemSetting.SystemSettingSpec> loadSystemSetting() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(SystemSetting::getSpec)
            .map(spec -> spec == null ? new SystemSetting.SystemSettingSpec() : spec)
            .switchIfEmpty(Mono.just(new SystemSetting.SystemSettingSpec()));
    }

    private CardRecord.CardRecordSpec ensureCardRecordSpec(CardRecord cardRecord) {
        if (cardRecord.getSpec() != null) {
            return cardRecord.getSpec();
        }
        var spec = new CardRecord.CardRecordSpec();
        spec.setCallSign("");
        spec.setCardType("QSO");
        spec.setCardVersion("");
        spec.setQsoRecordName("");
        spec.setCardDate("");
        spec.setCardTime("");
        spec.setCardRemarks("");
        spec.setCardSent(Boolean.FALSE);
        spec.setCardReceived(Boolean.FALSE);
        spec.setReceiptConfirmed(Boolean.FALSE);
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

        boolean isAutoEnabled(SystemSetting.SystemSettingSpec spec) {
            return switch (this) {
                case CARD_CREATED -> Boolean.TRUE.equals(spec.getAutoNotifyOnCardCreated());
                case CARD_SENT -> Boolean.TRUE.equals(spec.getAutoNotifyOnCardSent());
                case CARD_RECEIVED -> Boolean.TRUE.equals(spec.getAutoNotifyOnCardReceived());
            };
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
}

