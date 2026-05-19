package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.OfflineActivity;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.StationCard;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslPublicApiService {

    private static final Logger log = LoggerFactory.getLogger(QslPublicApiService.class);
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final String STATION_PROFILE_NAME = "qsl-station-profile-default";
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final int STATION_CARD_CACHE_SECONDS = 30;
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+$");
    private static final Pattern TELEPHONE_PATTERN = Pattern.compile("^[0-9+\\-\\s]{0,30}$");
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("^[A-Za-z0-9\\-]{0,20}$");
    private static final Pattern EXCHANGE_REQUEST_NAME_PATTERN = Pattern.compile("^EX(\\d{4,})$");
    private static final List<String> ALLOWED_SCENE_TYPES = List.of("QSO", "SWL", "ONLINE_EYEBALL", "EYEBALL");

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;
    private final QslConsoleActionService consoleActionService;
    private final AtomicReference<StationCardCacheEntry> stationCardCache = new AtomicReference<>(
        new StationCardCacheEntry(List.of(), Instant.EPOCH)
    );

    public QslPublicApiService(
        ReactiveExtensionClient client,
        QslAuditService qslAuditService,
        QslConsoleActionService consoleActionService
    ) {
        this.client = client;
        this.qslAuditService = qslAuditService;
        this.consoleActionService = consoleActionService;
    }

    public Mono<PublicQsoQueryResult> listPublicRecords(String callSign, String sceneType) {
        var normalizedCallSign = QslApiSupport.normalizeCallSign(callSign);
        var normalizedSceneType = normalizeSceneType(sceneType);
        if (normalizedCallSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "请提供呼号"));
        }
        if (!isValidCallSign(normalizedCallSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }
        if (!normalizedSceneType.isBlank() && !ALLOWED_SCENE_TYPES.contains(normalizedSceneType)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "场景类型不合法"));
        }

        var qsoItemsMono = client.listAll(QsoRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(qsoRecord -> qsoRecord.getSpec() != null)
            .filter(qsoRecord -> normalizedCallSign.equals(QslApiSupport.normalizeCallSign(qsoRecord.getSpec().getCallSign())))
            .map(qsoRecord -> new PublicQsoItem(
                qsoRecord.getMetadata().getName(),
                normalizedCallSign,
                nullToEmpty(qsoRecord.getSpec().getDate()),
                nullToEmpty(qsoRecord.getSpec().getTime()),
                nullToEmpty(qsoRecord.getSpec().getFreq()),
                nullToEmpty(qsoRecord.getSpec().getQth())
            ))
            .collectList();

        var cardItemsMono = client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .filter(cardRecord -> normalizedCallSign.equals(QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign())))
            .filter(cardRecord -> {
                if (normalizedSceneType.isBlank()) {
                    return true;
                }
                return normalizedSceneType.equals(normalizeSceneType(cardRecord.getSpec().getSceneType()));
            })
            .map(cardRecord -> new PublicCardItem(
                cardRecord.getMetadata().getName(),
                normalizedCallSign,
                nullToEmpty(cardRecord.getSpec().getCardType()),
                Boolean.TRUE.equals(cardRecord.getSpec().getCardSent()),
                Boolean.TRUE.equals(cardRecord.getSpec().getCardReceived()),
                Boolean.TRUE.equals(cardRecord.getSpec().getReceiptConfirmed()),
                nullToEmpty(cardRecord.getSpec().getCardDate())
            ))
            .collectList();

        return Mono.zip(qsoItemsMono, cardItemsMono)
            .map(tuple -> new PublicQsoQueryResult(
                normalizedCallSign,
                tuple.getT1(),
                tuple.getT2(),
                tuple.getT1().size() + tuple.getT2().size()
            ));
    }

    public Mono<PublicExchangeSubmitResult> submitExchangeRequest(PublicExchangeSubmitCommand command, String clientIp) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (!isValidCallSign(callSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }
        if (Boolean.TRUE.equals(command.useBureau()) && nullToEmpty(command.bureauName()).isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "选择卡片局模式时必须填写卡片局名称"));
        }
        if (Boolean.TRUE.equals(command.useBureau())
            && (nullToEmpty(command.postalCode()).isBlank() || nullToEmpty(command.address()).isBlank())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "选择卡片局模式时必须提供卡片局邮编和地址"));
        }
        if (!Boolean.TRUE.equals(command.useBureau())
            && (nullToEmpty(command.name()).isBlank()
                || nullToEmpty(command.telephone()).isBlank()
                || nullToEmpty(command.postalCode()).isBlank()
                || nullToEmpty(command.address()).isBlank())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "选择个人地址时必须填写姓名、电话、邮编和通信地址"));
        }
        if (!validateLength(command.bureauName(), 80)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片局名称长度不能超过80字符"));
        }
        if (!validateLength(command.name(), 60)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "联系人姓名长度不能超过60字符"));
        }
        if (!validateLength(command.address(), 200)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "通信地址长度不能超过200字符"));
        }
        if (!validateLength(command.remarks(), 500)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "备注长度不能超过500字符"));
        }
        if (!validateOptionalEmail(command.email())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮箱格式不合法"));
        }
        if (!validateOptionalTelephone(command.telephone())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "联系电话格式不合法"));
        }
        if (!validateOptionalPostalCode(command.postalCode())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "邮编格式不合法"));
        }

        var cardVersions = normalizeCardVersions(command.cardVersion());
        if (cardVersions.isEmpty()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "请选择卡片版本"));
        }
        if (cardVersions.size() > 2) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "最多只能选择两张卡片"));
        }

        return ensureNoPendingOnlineExchangeRequest(callSign)
            .then(Mono.defer(() -> validateSelectedStationCardVersions(cardVersions)))
            .flatMap(persistedCardVersion -> nextExchangeRequestName().flatMap(resourceName -> {
                var request = new ExchangeRequest();
                request.setMetadata(QslApiSupport.createMetadata(resourceName));

                var spec = new ExchangeRequest.ExchangeRequestSpec();
                spec.setSceneType("ONLINE_EYEBALL");
                spec.setCallSign(callSign);
                spec.setCardVersion(persistedCardVersion);
                spec.setUseBureau(Boolean.TRUE.equals(command.useBureau()));
                spec.setBureauName(nullToEmpty(command.bureauName()));
                spec.setEmail(nullToEmpty(command.email()));
                spec.setName(nullToEmpty(command.name()));
                spec.setTelephone(nullToEmpty(command.telephone()));
                spec.setPostalCode(nullToEmpty(command.postalCode()));
                spec.setAddress(nullToEmpty(command.address()));
                spec.setRemarks(nullToEmpty(command.remarks()));
                request.setSpec(spec);

                var status = new ExchangeRequest.ExchangeRequestStatus();
                status.setReviewStatus("待审核");
                status.setReviewReason("");
                status.setReviewedBy("");
                status.setReviewedAt("");
                request.setStatus(status);

                return client.create(request)
                    .flatMap(created -> qslAuditService.appendAuditLog(
                        "匿名提交换卡申请",
                        "exchange-request",
                        created.getMetadata().getName(),
                        "呼号=" + callSign + "，卡片版本=" + persistedCardVersion,
                        "匿名用户",
                        clientIp
                    ).thenReturn(created))
                    .flatMap(created -> requiresExchangeReview()
                        .flatMap(requiresReview -> {
                            if (requiresReview) {
                                return Mono.just(created);
                            }
                            return consoleActionService.reviewExchangeRequest(
                                    created.getMetadata().getName(),
                                    true,
                                    "系统自动审批通过",
                                    "系统自动审批",
                                    clientIp
                                )
                                .then(client.fetch(ExchangeRequest.class, created.getMetadata().getName())
                                    .defaultIfEmpty(created));
                        }))
                    .flatMap(created -> getPublicStationContact()
                        .map(contact -> new PublicExchangeSubmitResult(
                            created.getMetadata().getName(),
                            callSign,
                            created.getStatus().getReviewStatus(),
                            nullToEmpty(contact.stationAddress()),
                            QslApiSupport.nowText()
                        )));
            }));
    }

    private Mono<Boolean> requiresExchangeReview() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(systemSetting -> systemSetting.getSpec() == null
                || !Boolean.FALSE.equals(systemSetting.getSpec().getRequiresExchangeReview()))
            .defaultIfEmpty(Boolean.TRUE)
            .onErrorResume(error -> {
                log.warn("读取换卡审核开关失败，使用需要审核默认值。message={}", error.getMessage());
                return Mono.just(Boolean.TRUE);
            });
    }

    public Mono<List<PublicBureauItem>> listPublicBureaus() {
        return client.listAll(BureauEntry.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(bureau -> bureau.getMetadata() != null && bureau.getSpec() != null)
            .map(bureau -> {
                var spec = bureau.getSpec();
                return new PublicBureauItem(
                    nullToEmpty(bureau.getMetadata().getName()),
                    nullToEmpty(spec.getBureauName()).trim(),
                    nullToEmpty(spec.getPostalCode()).trim(),
                    nullToEmpty(spec.getAddress()).trim()
                );
            })
            .filter(item -> !item.bureauName().isBlank())
            .collectSortedList(Comparator.comparing(PublicBureauItem::bureauName, String.CASE_INSENSITIVE_ORDER));
    }

    public Mono<List<PublicStationCardItem>> listPublicStationCards() {
        var cached = stationCardCache.get();
        if (Instant.now().isBefore(cached.expireAt())) {
            return Mono.just(cached.items());
        }

        return loadCardVersionUsedCounts()
            .flatMap(usedCounts -> client.listAll(StationCard.class, EMPTY_OPTIONS, DEFAULT_SORT)
                .filter(stationCard -> stationCard.getMetadata() != null && stationCard.getSpec() != null)
                .map(stationCard -> toPublicStationCardItem(stationCard, usedCounts))
                .filter(item -> !item.cardVersion().isBlank())
                .collectSortedList(Comparator.comparingInt(PublicStationCardItem::sortOrder)
                    .thenComparing(PublicStationCardItem::cardVersion, String.CASE_INSENSITIVE_ORDER)))
            .doOnNext(items -> stationCardCache.set(new StationCardCacheEntry(
                List.copyOf(items),
                Instant.now().plusSeconds(STATION_CARD_CACHE_SECONDS)
            )))
            .onErrorResume(error -> {
                var fallback = stationCardCache.get();
                if (!fallback.items().isEmpty()) {
                    log.warn("读取公开卡片版本失败，返回上一次成功缓存。message={}", error.getMessage());
                    return Mono.just(fallback.items());
                }
                return Mono.error(error);
            });
    }

    public Mono<List<PublicOfflineActivityItem>> listPublicOfflineActivities() {
        return client.listAll(OfflineActivity.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(activity -> activity.getMetadata() != null && activity.getSpec() != null)
            .map(activity -> {
                var id = nullToEmpty(activity.getMetadata().getName()).trim();
                var spec = activity.getSpec();
                var name = nullToEmpty(spec.getActivityName()).trim();
                var date = nullToEmpty(spec.getActivityDate()).trim();
                var displayName = buildOfflineActivityDisplayName(id, name, date);
                return new PublicOfflineActivityItem(id, name, date, displayName);
            })
            .collectList();
    }

    public Mono<PublicOfflineExchangeConfirmResult> confirmOfflineExchange(
        PublicOfflineExchangeConfirmCommand command,
        String clientIp
    ) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (!isValidCallSign(callSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }

        var cardId = nullToEmpty(command.cardId()).trim().toUpperCase(Locale.ROOT);
        if (cardId.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片编号不能为空"));
        }

        var activityId = nullToEmpty(command.activityId()).trim();
        if (activityId.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "活动ID不能为空"));
        }

        if (!validateLength(activityId, 64)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "活动ID长度不能超过64字符"));
        }
        if (!validateLength(command.remarks(), 500)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "备注长度不能超过500字符"));
        }

        return client.fetch(CardRecord.class, cardId)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "未找到对应卡片记录")))
            .flatMap(cardRecord -> {
                if (cardRecord.getSpec() == null) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "卡片记录缺少业务字段"));
                }
                var spec = cardRecord.getSpec();
                var sceneType = normalizeSceneType(spec.getSceneType());
                if (!"EYEBALL".equals(sceneType)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该卡片不属于线下换卡场景"));
                }
                var offlineActivityName = nullToEmpty(spec.getOfflineActivityName()).trim();
                if (!activityId.equals(offlineActivityName)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "活动ID与卡片记录不匹配"));
                }

                var cardCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
                if (!cardCallSign.isBlank()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该卡片已被签收"));
                }

                spec.setCallSign(callSign);
                spec.setReceiptConfirmed(Boolean.TRUE);
                spec.setPublicReceiptRemarks(QslApiSupport.appendRemark(
                    spec.getPublicReceiptRemarks(),
                    command.remarks() == null ? "" : command.remarks().trim()
                ));
                QslCardStateTransitionSupport.applyReceiptConfirmedSideEffects(cardRecord);
                return mergeOfflineTemporaryReceivedCards(cardRecord, callSign, offlineActivityName)
                    .flatMap(client::update);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "前台线下换卡确认",
                "card-record",
                updated.getMetadata().getName(),
                "呼号=" + callSign + "，活动ID=" + activityId,
                "匿名用户",
                clientIp
            ).thenReturn(updated))
            .flatMap(updated -> getPublicStationContact()
                .map(contact -> new PublicOfflineExchangeConfirmResult(
                    updated.getMetadata().getName(),
                    callSign,
                    activityId,
                    nullToEmpty(contact.stationAddress()),
                    nullToEmpty(contact.stationEmail()),
                    QslApiSupport.nowText()
                )));
    }

    public Mono<PublicOfflineExchangePagePrefill> getOfflineExchangePagePrefill(String cardId) {
        var normalizedCardId = nullToEmpty(cardId).trim().toUpperCase(Locale.ROOT);
        if (normalizedCardId.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片编号不能为空"));
        }
        if (!validateLength(normalizedCardId, 128)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片编号长度不能超过128字符"));
        }

        return client.fetch(CardRecord.class, normalizedCardId)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "未找到对应卡片记录")))
            .flatMap(cardRecord -> {
                if (cardRecord.getSpec() == null) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "卡片记录缺少业务字段"));
                }
                var spec = cardRecord.getSpec();
                var sceneType = normalizeSceneType(spec.getSceneType());
                if (!"EYEBALL".equals(sceneType)) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "该卡片不属于线下换卡场景"));
                }

                var prefillCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
                var prefillActivityId = nullToEmpty(spec.getOfflineActivityName()).trim();
                var prefillRemarks = "";

                return Mono.just(new PublicOfflineExchangePagePrefill(
                    normalizedCardId,
                    prefillCallSign,
                    prefillActivityId,
                    prefillRemarks
                ));
            });
    }

    private Mono<CardRecord> mergeOfflineTemporaryReceivedCards(CardRecord activatedCard, String callSign,
        String offlineActivityName) {
        return Mono.just(activatedCard);
    }

    private void clearReceivedMailState(CardRecord.CardRecordSpec spec) {
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
    }

    public Mono<PublicCardPagePrefill> getOnlineExchangePagePrefill(String cardId, String callSign) {
        return getCardPagePrefillByCardIdAndCallSign(cardId, callSign, "ONLINE_EYEBALL");
    }

    public Mono<PublicCardPagePrefill> getReceiptPagePrefill(String cardId, String callSign) {
        return getCardPagePrefillByCardIdAndCallSign(cardId, callSign, "QSO", "SWL", "ONLINE_EYEBALL");
    }

    public Mono<PublicReceiptConfirmResult> confirmReceipt(PublicReceiptConfirmCommand command, String clientIp) {
        var callSign = QslApiSupport.normalizeCallSign(command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (!isValidCallSign(callSign)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号格式不合法"));
        }

        var cardId = nullToEmpty(command.cardId()).trim().toUpperCase(Locale.ROOT);
        if (cardId.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "卡片编号不能为空"));
        }
        if (!validateLength(command.remarks(), 500)) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "签收备注长度不能超过500字符"));
        }

        return client.fetch(CardRecord.class, cardId)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "未找到可签收的卡片记录")))
            .flatMap(cardRecord -> {
                if (cardRecord.getSpec() == null) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "卡片记录缺少业务字段，无法签收"));
                }
                var cardCallSign = QslApiSupport.normalizeCallSign(cardRecord.getSpec().getCallSign());
                var cardSceneType = normalizeSceneType(cardRecord.getSpec().getSceneType());

                var offlineEyeballScene = "EYEBALL".equals(cardSceneType);
                if (offlineEyeballScene && cardCallSign.isBlank()) {
                    cardRecord.getSpec().setCallSign(callSign);
                } else {
                    if (offlineEyeballScene) {
                        return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "QSL-422-0001", "该卡片已被签收"));
                    }
                    if (!callSign.equals(cardCallSign)) {
                        return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "QSL-422-0001", "卡片和呼号不匹配"));
                    }
                }
                cardRecord.getSpec().setReceiptConfirmed(Boolean.TRUE);
                cardRecord.getSpec().setPublicReceiptRemarks(QslApiSupport.appendRemark(
                    cardRecord.getSpec().getPublicReceiptRemarks(),
                    command.remarks() == null ? "" : command.remarks().trim()
                ));
                QslCardStateTransitionSupport.applyReceiptConfirmedSideEffects(cardRecord);
                return client.update(cardRecord);
            })
            .flatMap(updated -> qslAuditService.appendAuditLog(
                "公开签收确认",
                "card-record",
                updated.getMetadata().getName(),
                "呼号=" + callSign + "，卡片ID=" + updated.getMetadata().getName(),
                "匿名用户",
                clientIp
            ).thenReturn(updated))
            .map(updated -> new PublicReceiptConfirmResult(
                updated.getMetadata().getName(),
                callSign,
                QslApiSupport.normalizeCardType(updated.getSpec().getCardType()),
                QslApiSupport.nowText()
            ));
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private List<String> normalizeCardVersions(String raw) {
        var normalized = nullToEmpty(raw)
            .replace('，', ',')
            .replace('、', ',')
            .replace('；', ',')
            .replace(';', ',');
        var values = normalized.split(",");
        var deduplicated = new LinkedHashMap<String, String>();
        for (var value : values) {
            var version = value == null ? "" : value.trim();
            if (version.isBlank()) {
                continue;
            }
            deduplicated.putIfAbsent(normalizeVersionKey(version), version);
        }
        return List.copyOf(deduplicated.values());
    }

    private String normalizeVersionKey(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private int safeInventoryTotal(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    private int safeSortOrder(Integer value) {
        return value == null || value <= 0 ? Integer.MAX_VALUE : value;
    }

    private Mono<Map<String, Integer>> loadCardVersionUsedCounts() {
        return client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(cardRecord -> cardRecord.getSpec() != null)
            .map(cardRecord -> normalizeCardVersions(cardRecord.getSpec().getCardVersion()))
            .collectList()
            .map(groups -> {
                Map<String, Integer> counter = new LinkedHashMap<>();
                for (var group : groups) {
                    for (var version : group) {
                        var key = normalizeVersionKey(version);
                        if (!key.isBlank()) {
                            counter.merge(key, 1, Integer::sum);
                        }
                    }
                }
                return counter;
            });
    }

    private PublicStationCardItem toPublicStationCardItem(StationCard stationCard, Map<String, Integer> usedCounts) {
        var spec = stationCard.getSpec();
        var cardVersion = nullToEmpty(spec.getCardVersion()).trim();
        var usedCount = usedCounts.getOrDefault(normalizeVersionKey(cardVersion), 0);
        var availableInventory = safeInventoryTotal(spec.getAvailableInventory());
        var previewUrl = nullToEmpty(spec.getImageThumbnailUrl()).isBlank()
            ? nullToEmpty(spec.getImagePermalink())
            : nullToEmpty(spec.getImageThumbnailUrl());
        return new PublicStationCardItem(
            nullToEmpty(stationCard.getMetadata().getName()),
            cardVersion,
            previewUrl,
            safeInventoryTotal(spec.getVersionTotal()),
            availableInventory,
            usedCount,
            Math.max(availableInventory - usedCount, 0),
            safeSortOrder(spec.getSortOrder())
        );
    }

    private Mono<String> validateSelectedStationCardVersions(List<String> requestedVersions) {
        return listPublicStationCards()
            .map(items -> {
                Map<String, PublicStationCardItem> itemMap = new LinkedHashMap<>();
                for (var item : items) {
                    itemMap.putIfAbsent(normalizeVersionKey(item.cardVersion()), item);
                }

                var persisted = new LinkedHashMap<String, String>();
                for (var requestedVersion : requestedVersions) {
                    var item = itemMap.get(normalizeVersionKey(requestedVersion));
                    if (item == null) {
                        throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                            "卡片版本不存在：" + requestedVersion);
                    }
                    if (item.remainingInventory() <= 0) {
                        throw new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                            "卡片版本已无库存余量：" + item.cardVersion());
                    }
                    persisted.putIfAbsent(normalizeVersionKey(item.cardVersion()), item.cardVersion());
                }
                return String.join("、", persisted.values());
            });
    }

    private Mono<Void> ensureNoPendingOnlineExchangeRequest(String callSign) {
        return client.listAll(ExchangeRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .filter(request -> request.getSpec() != null)
            .filter(request -> "ONLINE_EYEBALL".equals(normalizeSceneType(request.getSpec().getSceneType())))
            .filter(request -> callSign.equals(QslApiSupport.normalizeCallSign(request.getSpec().getCallSign())))
            .filter(request -> isPendingReview(request.getStatus()))
            .hasElements()
            .flatMap(exists -> {
                if (Boolean.TRUE.equals(exists)) {
                    return Mono.error(new QslApiException(
                        HttpStatus.CONFLICT,
                        "QSL-409-0001",
                        "该呼号已有待审核换卡申请，请等待后台审核后再提交"
                    ));
                }
                return Mono.<Void>empty();
            });
    }

    private Mono<String> nextExchangeRequestName() {
        return client.listAll(ExchangeRequest.class, EMPTY_OPTIONS, DEFAULT_SORT)
            .map(request -> request.getMetadata() == null ? "" : request.getMetadata().getName())
            .map(name -> extractSequence(name, EXCHANGE_REQUEST_NAME_PATTERN))
            .reduce(0, Math::max)
            .map(next -> String.format("EX%04d", next + 1));
    }

    private int extractSequence(String name, Pattern pattern) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        var matcher = pattern.matcher(name.trim());
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private boolean isPendingReview(ExchangeRequest.ExchangeRequestStatus status) {
        var reviewStatus = status == null ? "" : nullToEmpty(status.getReviewStatus()).trim();
        return reviewStatus.isBlank() || "待审核".equals(reviewStatus);
    }

    private boolean isValidCallSign(String callSign) {
        return CALL_SIGN_PATTERN.matcher(callSign).matches();
    }

    private boolean validateLength(String value, int maxLength) {
        return nullToEmpty(value).trim().length() <= maxLength;
    }

    private boolean validateOptionalEmail(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        if (normalized.length() > 120) {
            return false;
        }
        return EMAIL_PATTERN.matcher(normalized).matches();
    }

    private boolean validateOptionalTelephone(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        return TELEPHONE_PATTERN.matcher(normalized).matches();
    }

    private boolean validateOptionalPostalCode(String value) {
        var normalized = nullToEmpty(value).trim();
        if (normalized.isBlank()) {
            return true;
        }
        return POSTAL_CODE_PATTERN.matcher(normalized).matches();
    }

    public Mono<PublicStationContact> getPublicStationContact() {
        return client.fetch(StationProfile.class, STATION_PROFILE_NAME)
            .map(stationProfile -> {
                var spec = stationProfile.getSpec();
                if (spec == null) {
                    return new PublicStationContact("", "");
                }
                var callSign = nullToEmpty(spec.getMyCallSign()).trim().toUpperCase(Locale.ROOT);
                var name = nullToEmpty(spec.getMyName()).trim();
                var telephone = nullToEmpty(spec.getMyTelephone()).trim();
                var postalCode = nullToEmpty(spec.getMyPostalCode()).trim();
                var address = nullToEmpty(spec.getMyAddress()).trim();
                var email = nullToEmpty(spec.getMyEmail()).trim();

                var recipient = (name.isBlank() ? "" : name) + "（" + (callSign.isBlank() ? "-" : callSign) + "）（收）";
                var stationAddressText = String.join("\n",
                    "邮编：" + (postalCode.isBlank() ? "-" : postalCode),
                    "地址：" + (address.isBlank() ? "-" : address),
                    "收件人：" + recipient,
                    "联系电话：" + (telephone.isBlank() ? "-" : telephone),
                    "电子邮箱：" + (email.isBlank() ? "-" : email)
                );
                return new PublicStationContact(
                    stationAddressText,
                    email
                );
            })
            .defaultIfEmpty(new PublicStationContact("", ""));
    }

    private String normalizeSceneType(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String firstNotBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private String buildOfflineActivityDisplayName(String id, String name, String date) {
        var safeName = name.isBlank() ? id : name;
        if (date.isBlank()) {
            return safeName;
        }
        return "【" + date + "】" + safeName;
    }

    private Mono<PublicCardPagePrefill> getCardPagePrefillByCardIdAndCallSign(
        String cardId,
        String providedCallSign,
        String... allowedSceneTypes
    ) {
        var normalizedCardId = nullToEmpty(cardId).trim().toUpperCase(Locale.ROOT);
        var normalizedProvidedCallSign = QslApiSupport.normalizeCallSign(providedCallSign);
        if (normalizedCardId.isBlank() || normalizedProvidedCallSign.isBlank()) {
            return Mono.just(new PublicCardPagePrefill("", "", ""));
        }
        if (!validateLength(normalizedCardId, 128) || !isValidCallSign(normalizedProvidedCallSign)) {
            return Mono.just(new PublicCardPagePrefill("", "", ""));
        }

        var allowed = List.of(allowedSceneTypes == null ? new String[0] : allowedSceneTypes);
        return client.fetch(CardRecord.class, normalizedCardId)
            .flatMap(cardRecord -> {
                if (cardRecord == null || cardRecord.getSpec() == null) {
                    return Mono.just(new PublicCardPagePrefill("", "", ""));
                }
                var spec = cardRecord.getSpec();
                var cardCallSign = QslApiSupport.normalizeCallSign(spec.getCallSign());
                if (cardCallSign.isBlank() || !normalizedProvidedCallSign.equals(cardCallSign)) {
                    return Mono.just(new PublicCardPagePrefill("", "", ""));
                }

                var sceneType = normalizeSceneType(spec.getSceneType());
                if (!allowed.contains(sceneType)) {
                    return Mono.just(new PublicCardPagePrefill("", "", ""));
                }

                var remarks = firstNotBlank(
                    nullToEmpty(spec.getPublicReceiptRemarks()).trim(),
                    nullToEmpty(spec.getBusinessRemarks()).trim(),
                    nullToEmpty(spec.getCardRemarks()).trim()
                );
                return Mono.just(new PublicCardPagePrefill(
                    normalizedCardId,
                    cardCallSign,
                    remarks
                ));
            })
            .defaultIfEmpty(new PublicCardPagePrefill("", "", ""));
    }

    public record PublicQsoQueryResult(
        String callSign,
        List<PublicQsoItem> qsoItems,
        List<PublicCardItem> cardItems,
        int total
    ) {
    }

    public record PublicQsoItem(
        String id,
        String callSign,
        String date,
        String time,
        String freq,
        String qth
    ) {
    }

    public record PublicCardItem(
        String id,
        String callSign,
        String cardType,
        boolean cardSent,
        boolean cardReceived,
        boolean receiptConfirmed,
        String cardDate
    ) {
    }

    public record PublicExchangeSubmitCommand(
        String callSign,
        Boolean useBureau,
        String bureauName,
        String email,
        String name,
        String telephone,
        String postalCode,
        String address,
        String remarks,
        String cardVersion
    ) {
    }

    public record PublicExchangeSubmitResult(
        String requestName,
        String callSign,
        String reviewStatus,
        String stationAddress,
        String submittedAt
    ) {
    }

    public record PublicBureauItem(
        String bureauId,
        String bureauName,
        String postalCode,
        String address
    ) {
    }

    public record PublicStationCardItem(
        String cardId,
        String cardVersion,
        String previewUrl,
        int versionTotal,
        int availableInventory,
        int usedCount,
        int remainingInventory,
        int sortOrder
    ) {
    }

    private record StationCardCacheEntry(List<PublicStationCardItem> items, Instant expireAt) {
    }

    public record PublicOfflineExchangeConfirmCommand(
        String callSign,
        String cardId,
        String activityId,
        String remarks
    ) {
    }

    public record PublicOfflineExchangeConfirmResult(
        String cardRecordName,
        String callSign,
        String activityId,
        String stationAddress,
        String stationEmail,
        String confirmedAt
    ) {
    }

    public record PublicOfflineExchangePagePrefill(
        String cardId,
        String callSign,
        String activityId,
        String remarks
    ) {
    }

    public record PublicCardPagePrefill(
        String cardId,
        String callSign,
        String remarks
    ) {
    }

    public record PublicOfflineActivityItem(
        String activityId,
        String activityName,
        String activityDate,
        String displayName
    ) {
    }

    public record PublicStationContact(
        String stationAddress,
        String stationEmail
    ) {
    }

    public record PublicReceiptConfirmCommand(
        String callSign,
        String cardId,
        String remarks,
        String sceneType
    ) {
    }

    public record PublicReceiptConfirmResult(
        String cardRecordName,
        String callSign,
        String cardType,
        String confirmedAt
    ) {
    }
}
