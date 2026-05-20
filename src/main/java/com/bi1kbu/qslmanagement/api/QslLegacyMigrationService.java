package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.OfflineExchangeCard;
import com.bi1kbu.qslmanagement.extension.model.ReceiveRecord;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslLegacyMigrationService {

    private static final Logger log = LoggerFactory.getLogger(QslLegacyMigrationService.class);
    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final Pattern RECEIVE_CODE_DATE_PATTERN = Pattern.compile(".*?(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern CARD_SEQUENCE_PATTERN = Pattern.compile("^C(\\d+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RECEIVE_SEQUENCE_PATTERN = Pattern.compile("^R(\\d+)-\\d{8}$", Pattern.CASE_INSENSITIVE);
    private static final String CONFIRM_TEXT = "确认迁移旧版本数据";

    private final ReactiveExtensionClient client;
    private final QslAuditService qslAuditService;

    public QslLegacyMigrationService(ReactiveExtensionClient client, QslAuditService qslAuditService) {
        this.client = client;
        this.qslAuditService = qslAuditService;
    }

    public Mono<LegacyMigrationResult> precheckLegacyMigration() {
        return loadSnapshot()
            .map(snapshot -> buildPlan(snapshot, false).result());
    }

    public Mono<LegacyMigrationResult> executeLegacyMigration(LegacyMigrationCommand command, String operator,
        String clientIp) {
        if (!CONFIRM_TEXT.equals(nullToEmpty(command.confirmText()).trim())) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                "确认文字不正确，不能执行旧版本迁移"));
        }

        return loadSnapshot()
            .flatMap(snapshot -> {
                var plan = buildPlan(snapshot, true);
                return executePlan(plan)
                    .then(qslAuditService.appendAuditLog(
                        "执行旧版本一键迁移",
                        "legacy-migration",
                        "current-storage",
                        plan.result().message(),
                        operator,
                        clientIp
                    ).onErrorResume(error -> {
                        log.warn("旧版本迁移审计日志写入失败，忽略并继续。message={}", error.getMessage());
                        return Mono.empty();
                    }))
                    .thenReturn(plan.result());
            });
    }

    private Mono<MigrationSnapshot> loadSnapshot() {
        var cardsMono = client.listAll(CardRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();
        var receivesMono = client.listAll(ReceiveRecord.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();
        var offlineCardsMono = client.listAll(OfflineExchangeCard.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();
        var settingsMono = client.listAll(SystemSetting.class, EMPTY_OPTIONS, DEFAULT_SORT).collectList();
        return Mono.zip(cardsMono, receivesMono, offlineCardsMono, settingsMono)
            .map(tuple -> new MigrationSnapshot(tuple.getT1(), tuple.getT2(), tuple.getT3(), tuple.getT4()));
    }

    private MigrationPlan buildPlan(MigrationSnapshot snapshot, boolean execute) {
        var existingReceiveNames = resourceNames(snapshot.receiveRecords());
        var existingOfflineNames = resourceNames(snapshot.offlineExchangeCards());
        var stationPlaceholders = snapshot.cardRecords().stream()
            .filter(this::isStationCardPlaceholder)
            .toList();
        var businessCards = snapshot.cardRecords().stream()
            .filter(card -> !isStationCardPlaceholder(card))
            .toList();
        var receiveRecords = buildReceiveRecords(businessCards, existingReceiveNames);
        var linkedReceiveStates = buildLinkedReceiveStates(receiveRecords.toCreate(), snapshot.receiveRecords());
        var legacyAutoReceiveCards = businessCards.stream()
            .filter(this::isLegacyAutoReceiveCard)
            .toList();
        var retainedCards = businessCards.stream()
            .filter(card -> !isLegacyAutoReceiveCard(card))
            .toList();
        var cardsToUpdate = retainedCards.stream()
            .filter(card -> hasExtractedReceiveFields(card)
                || linkedReceiveStates.containsKey(normalizeResourceName(resourceName(card))))
            .peek(card -> stripExtractedReceiveFields(
                card,
                linkedReceiveStates.get(normalizeResourceName(resourceName(card)))
            ))
            .toList();
        var offlineCards = buildOfflineExchangeCards(retainedCards, existingOfflineNames);
        var maxCardSequence = maxCardSequence(retainedCards);
        var maxReceiveSequence = Math.max(
            maxReceiveSequence(snapshot.receiveRecords()),
            maxReceiveSequence(receiveRecords.toCreate())
        );
        var settingsToUpdate = buildSettingsToUpdate(snapshot.systemSettings(), maxCardSequence, maxReceiveSequence);
        var warnings = buildWarnings(receiveRecords, offlineCards, stationPlaceholders, legacyAutoReceiveCards);
        var result = new LegacyMigrationResult(
            execute ? "迁移完成" : "预检完成",
            "旧版本迁移：卡片记录 " + snapshot.cardRecords().size()
                + " 条，保留 " + retainedCards.size()
                + " 条；待创建收卡记录 " + receiveRecords.toCreate().size()
                + " 条，待创建线下换卡卡片 " + offlineCards.toCreate().size() + " 条。",
            "current-storage",
            snapshot.cardRecords().size(),
            retainedCards.size(),
            stationPlaceholders.size(),
            legacyAutoReceiveCards.size(),
            cardsToUpdate.size(),
            receiveRecords.toCreate().size(),
            receiveRecords.skippedExisting(),
            receiveRecords.matchedCount(),
            receiveRecords.unmatchedCount(),
            offlineCards.toCreate().size(),
            offlineCards.skippedExisting(),
            settingsToUpdate.size(),
            maxCardSequence,
            maxReceiveSequence,
            warnings
        );
        return new MigrationPlan(
            result,
            receiveRecords.toCreate(),
            offlineCards.toCreate(),
            cardsToUpdate,
            stationPlaceholders,
            legacyAutoReceiveCards,
            settingsToUpdate
        );
    }

    private Mono<Void> executePlan(MigrationPlan plan) {
        return Flux.fromIterable(plan.receiveRecordsToCreate())
            .concatMap(client::create)
            .thenMany(Flux.fromIterable(plan.offlineExchangeCardsToCreate()).concatMap(client::create))
            .thenMany(Flux.fromIterable(plan.cardRecordsToUpdate()).concatMap(client::update))
            .thenMany(Flux.fromIterable(plan.stationPlaceholdersToDelete()).concatMap(client::delete))
            .thenMany(Flux.fromIterable(plan.legacyAutoReceiveCardsToDelete()).concatMap(client::delete))
            .thenMany(Flux.fromIterable(plan.systemSettingsToUpdate()).concatMap(client::update))
            .then();
    }

    private ReceiveBuildResult buildReceiveRecords(List<CardRecord> cardRecords, LinkedHashSet<String> existingNames) {
        var grouped = new LinkedHashMap<String, ReceiveGroup>();
        for (var cardRecord : cardRecords) {
            var spec = cardRecord.getSpec();
            if (spec == null || !Boolean.TRUE.equals(spec.getCardReceived())) {
                continue;
            }
            for (var code : splitValues(spec.getReceivedRecordCodes())) {
                var group = grouped.computeIfAbsent(code, ignored -> new ReceiveGroup(cardRecord));
                if (isLegacyAutoReceiveCard(cardRecord)) {
                    group.autoCards().add(cardRecord);
                } else {
                    group.linkedCards().add(cardRecord);
                }
                if (!isBlank(spec.getReceivedRemarks())) {
                    group.remarks().add(spec.getReceivedRemarks().trim());
                }
            }
        }

        var records = new ArrayList<ReceiveRecord>();
        long skippedExisting = 0;
        long matchedCount = 0;
        long unmatchedCount = 0;
        for (var entry : grouped.entrySet()) {
            if (existingNames.contains(entry.getKey())) {
                skippedExisting += 1;
                continue;
            }
            var receiveRecord = buildReceiveRecord(entry.getKey(), entry.getValue());
            records.add(receiveRecord);
            if ("自动匹配".equals(receiveRecord.getSpec().getMatchStatus())) {
                matchedCount += 1;
            } else {
                unmatchedCount += 1;
            }
        }
        return new ReceiveBuildResult(records, skippedExisting, matchedCount, unmatchedCount);
    }

    private ReceiveRecord buildReceiveRecord(String code, ReceiveGroup group) {
        var linkedCards = group.linkedCards();
        var allCards = linkedCards.isEmpty() ? group.autoCards() : linkedCards;
        var firstCard = allCards.isEmpty() ? group.firstCard() : allCards.get(0);
        var spec = firstCard.getSpec();
        var outboundCardNames = linkedCards.stream()
            .map(this::resourceName)
            .filter(name -> !name.isBlank())
            .toList();
        var receivedDate = dateFromReceiveCode(code);
        if (receivedDate.isBlank()) {
            receivedDate = firstNonEmptyReceivedDate(allCards);
        }
        var receivedAt = firstNonEmptyReceivedAt(allCards);
        if (receivedAt.isBlank() && !receivedDate.isBlank()) {
            receivedAt = receivedDate + " 00:00:00";
        }
        var offlineActivities = new LinkedHashSet<String>();
        for (var card : allCards) {
            if (card.getSpec() != null && !isBlank(card.getSpec().getOfflineActivityName())) {
                offlineActivities.add(card.getSpec().getOfflineActivityName().trim());
            }
        }

        var receiveRecord = new ReceiveRecord();
        receiveRecord.setMetadata(QslApiSupport.createMetadata(code));
        var receiveSpec = new ReceiveRecord.ReceiveRecordSpec();
        receiveSpec.setCallSign(spec == null ? "" : nullToEmpty(spec.getCallSign()));
        receiveSpec.setCardType(spec == null ? "QSO" : defaultIfBlank(spec.getCardType(), "QSO"));
        receiveSpec.setBusinessType(resolveBusinessType(firstCard));
        receiveSpec.setOfflineActivityName(String.join("、", offlineActivities));
        receiveSpec.setReceivedDate(receivedDate);
        receiveSpec.setReceivedAt(receivedAt);
        receiveSpec.setOutboundCardNames(String.join(", ", outboundCardNames));
        receiveSpec.setMatchStatus(outboundCardNames.isEmpty() ? "未匹配" : "自动匹配");
        receiveSpec.setMatchReason(outboundCardNames.isEmpty()
            ? "历史自动收卡记录，已转为独立收卡事实"
            : "历史收卡编号聚合关联");
        receiveSpec.setRemarks(String.join("；", group.remarks()));
        receiveRecord.setSpec(receiveSpec);
        var status = new ReceiveRecord.ReceiveRecordStatus();
        status.setSyncStatus("MIGRATED");
        receiveRecord.setStatus(status);
        return receiveRecord;
    }

    private OfflineBuildResult buildOfflineExchangeCards(List<CardRecord> cardRecords,
        LinkedHashSet<String> existingNames) {
        var cards = new ArrayList<OfflineExchangeCard>();
        long skippedExisting = 0;
        for (var cardRecord : cardRecords) {
            var spec = cardRecord.getSpec();
            if (spec == null || !"EYEBALL".equalsIgnoreCase(nullToEmpty(spec.getSceneType()).trim())) {
                continue;
            }
            if (isBlank(spec.getOfflineActivityName()) || isLegacyAutoReceiveCard(cardRecord)) {
                continue;
            }
            var name = "OEC-" + resourceName(cardRecord);
            if (existingNames.contains(name)) {
                skippedExisting += 1;
                continue;
            }
            cards.add(buildOfflineExchangeCard(name, cardRecord));
        }
        return new OfflineBuildResult(cards, skippedExisting);
    }

    private OfflineExchangeCard buildOfflineExchangeCard(String name, CardRecord cardRecord) {
        var spec = cardRecord.getSpec();
        var offlineCard = new OfflineExchangeCard();
        offlineCard.setMetadata(QslApiSupport.createMetadata(name));
        var offlineSpec = new OfflineExchangeCard.OfflineExchangeCardSpec();
        offlineSpec.setCardRecordName(resourceName(cardRecord));
        offlineSpec.setOfflineActivityName(nullToEmpty(spec.getOfflineActivityName()));
        offlineSpec.setCallSign(nullToEmpty(spec.getCallSign()));
        offlineSpec.setCardType(defaultIfBlank(spec.getCardType(), "EYEBALL"));
        offlineSpec.setCardVersion(nullToEmpty(spec.getCardVersion()));
        offlineSpec.setClaimStatus(resolveOfflineClaimStatus(spec));
        offlineSpec.setSentStatus(Boolean.TRUE.equals(spec.getCardSent()) || Boolean.TRUE.equals(spec.getReceiptConfirmed())
            ? "已发出"
            : "待发出");
        offlineSpec.setSentAt(nullToEmpty(spec.getSentAt()));
        offlineSpec.setRemarks(joinNonEmpty(
            spec.getBusinessRemarks(),
            spec.getCardRemarks(),
            spec.getPublicReceiptRemarks()
        ));
        offlineCard.setSpec(offlineSpec);
        var status = new OfflineExchangeCard.OfflineExchangeCardStatus();
        status.setFlowStatus(cardRecord.getStatus() == null ? "" : nullToEmpty(cardRecord.getStatus().getFlowStatus()));
        offlineCard.setStatus(status);
        return offlineCard;
    }

    private List<SystemSetting> buildSettingsToUpdate(List<SystemSetting> systemSettings, int maxCardSequence,
        int maxReceiveSequence) {
        var settingsToUpdate = new ArrayList<SystemSetting>();
        for (var setting : systemSettings) {
            if (setting.getSpec() == null) {
                setting.setSpec(new SystemSetting.SystemSettingSpec());
            }
            var spec = setting.getSpec();
            var changed = false;
            if (spec.getCardRecordSequence() == null || spec.getCardRecordSequence() < maxCardSequence) {
                spec.setCardRecordSequence(maxCardSequence);
                changed = true;
            }
            if (spec.getReceiveRecordSequence() == null || spec.getReceiveRecordSequence() < maxReceiveSequence) {
                spec.setReceiveRecordSequence(maxReceiveSequence);
                changed = true;
            }
            if (changed) {
                if (setting.getStatus() == null) {
                    setting.setStatus(new SystemSetting.SystemSettingStatus());
                }
                setting.getStatus().setLastModifiedAt(QslApiSupport.nowText());
                setting.getStatus().setLastModifiedBy("旧版本迁移");
                settingsToUpdate.add(setting);
            }
        }
        return settingsToUpdate;
    }

    private List<String> buildWarnings(ReceiveBuildResult receiveRecords, OfflineBuildResult offlineCards,
        List<CardRecord> stationPlaceholders, List<CardRecord> legacyAutoReceiveCards) {
        var warnings = new ArrayList<String>();
        if (receiveRecords.skippedExisting() > 0) {
            warnings.add("已有同名收卡记录 " + receiveRecords.skippedExisting() + " 条，执行时将跳过创建。");
        }
        if (offlineCards.skippedExisting() > 0) {
            warnings.add("已有同名线下换卡卡片 " + offlineCards.skippedExisting() + " 条，执行时将跳过创建。");
        }
        if (!stationPlaceholders.isEmpty()) {
            warnings.add("将删除误写入卡片记录的本台卡片版本占位记录 " + stationPlaceholders.size() + " 条。");
        }
        if (!legacyAutoReceiveCards.isEmpty()) {
            warnings.add("将删除旧版自动收卡临时卡片 " + legacyAutoReceiveCards.size() + " 条。");
        }
        return warnings;
    }

    private boolean isLegacyAutoReceiveCard(CardRecord cardRecord) {
        var spec = cardRecord.getSpec();
        return spec != null
            && "EYEBALL".equalsIgnoreCase(nullToEmpty(spec.getSceneType()).trim())
            && "自动创建EYEBALL卡片".equals(nullToEmpty(spec.getBusinessRemarks()).trim())
            && Boolean.TRUE.equals(spec.getCardReceived())
            && !Boolean.TRUE.equals(spec.getCardIssued())
            && !Boolean.TRUE.equals(spec.getCardSent())
            && !Boolean.TRUE.equals(spec.getReceiptConfirmed());
    }

    private boolean isStationCardPlaceholder(CardRecord cardRecord) {
        var spec = cardRecord.getSpec();
        var name = resourceName(cardRecord);
        return spec != null
            && name.startsWith("qsl-station-card-")
            && isBlank(spec.getCallSign())
            && isBlank(spec.getQsoRecordName())
            && SetOf("QSO", "SWL").contains(normalize(spec.getCardType()))
            && SetOf("QSO", "SWL").contains(normalize(spec.getSceneType()));
    }

    private boolean hasExtractedReceiveFields(CardRecord cardRecord) {
        var spec = cardRecord.getSpec();
        return spec != null
            && (!isBlank(spec.getReceivedRecordCodes())
                || !isBlank(spec.getReceivedAt())
                || !isBlank(spec.getReceivedRemarks())
                || !isBlank(spec.getReceivedMailStatus())
                || !isBlank(spec.getReceivedMailSentAt())
                || !isBlank(spec.getReceivedMailLastError()));
    }

    private Map<String, LinkedReceiveState> buildLinkedReceiveStates(List<ReceiveRecord> toCreate,
        List<ReceiveRecord> existingRecords) {
        var states = new LinkedHashMap<String, LinkedReceiveState>();
        var records = new ArrayList<ReceiveRecord>();
        records.addAll(existingRecords);
        records.addAll(toCreate);
        for (var receiveRecord : records) {
            var spec = receiveRecord.getSpec();
            if (spec == null) {
                continue;
            }
            var receivedDate = nullToEmpty(spec.getReceivedDate()).trim();
            var receivedAt = defaultIfBlank(spec.getReceivedAt(),
                receivedDate.isBlank() ? "" : receivedDate + " 00:00:00");
            for (var cardName : splitValues(spec.getOutboundCardNames())) {
                if (!isFormalCardName(cardName)) {
                    continue;
                }
                var key = normalizeResourceName(cardName);
                var current = states.get(key);
                if (current == null || isEarlierReceivedAt(receivedAt, current.receivedAt())) {
                    states.put(key, new LinkedReceiveState(receivedAt));
                }
            }
        }
        return states;
    }

    private void stripExtractedReceiveFields(CardRecord cardRecord, LinkedReceiveState linkedReceiveState) {
        var spec = cardRecord.getSpec();
        if (linkedReceiveState == null) {
            spec.setCardReceived(Boolean.FALSE);
            spec.setReceivedAt("");
        } else {
            spec.setCardReceived(Boolean.TRUE);
            spec.setReceivedAt(linkedReceiveState.receivedAt());
        }
        spec.setReceivedRemarks("");
        spec.setReceivedMailStatus("");
        spec.setReceivedMailSentAt("");
        spec.setReceivedMailLastError("");
        spec.setReceivedRecordCodes("");
        if (linkedReceiveState != null) {
            var status = cardRecord.getStatus() == null
                ? new CardRecord.CardRecordStatus()
                : cardRecord.getStatus();
            status.setFlowStatus("已收卡片");
            cardRecord.setStatus(status);
        } else if (cardRecord.getStatus() != null
            && "已收卡片".equals(nullToEmpty(cardRecord.getStatus().getFlowStatus()).trim())) {
            cardRecord.getStatus().setFlowStatus(Boolean.TRUE.equals(spec.getCardSent()) ? "已发卡片" : "已制卡");
        }
    }

    private boolean isFormalCardName(String value) {
        return CARD_SEQUENCE_PATTERN.matcher(nullToEmpty(value).trim()).matches();
    }

    private boolean isEarlierReceivedAt(String candidate, String current) {
        var normalizedCandidate = nullToEmpty(candidate).trim();
        var normalizedCurrent = nullToEmpty(current).trim();
        if (normalizedCandidate.isBlank()) {
            return normalizedCurrent.isBlank();
        }
        if (normalizedCurrent.isBlank()) {
            return true;
        }
        return normalizedCandidate.compareTo(normalizedCurrent) < 0;
    }

    private int maxCardSequence(List<CardRecord> cardRecords) {
        var max = 0;
        for (var cardRecord : cardRecords) {
            var matcher = CARD_SEQUENCE_PATTERN.matcher(resourceName(cardRecord));
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    private int maxReceiveSequence(List<ReceiveRecord> receiveRecords) {
        var max = 0;
        for (var receiveRecord : receiveRecords) {
            var matcher = RECEIVE_SEQUENCE_PATTERN.matcher(resourceName(receiveRecord));
            if (matcher.matches()) {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            }
        }
        return max;
    }

    private String resolveBusinessType(CardRecord cardRecord) {
        var spec = cardRecord.getSpec();
        var sceneType = normalize(spec == null ? "" : spec.getSceneType());
        var cardType = normalize(spec == null ? "" : spec.getCardType());
        if ("EYEBALL".equals(sceneType)) {
            return "OFFLINE_EYEBALL";
        }
        if ("ONLINE_EYEBALL".equals(sceneType)) {
            return "ONLINE_EYEBALL";
        }
        if ("SWL".equals(cardType) || "SWL".equals(sceneType)) {
            return "SWL";
        }
        if ("QSO".equals(cardType) || "QSO".equals(sceneType)) {
            return "QSO";
        }
        return !sceneType.isBlank() ? sceneType : (!cardType.isBlank() ? cardType : "UNKNOWN");
    }

    private String resolveOfflineClaimStatus(CardRecord.CardRecordSpec spec) {
        if (isBlank(spec.getCallSign())) {
            return "待认领";
        }
        return Boolean.TRUE.equals(spec.getReceiptConfirmed()) ? "已认领" : "人工绑定";
    }

    private String dateFromReceiveCode(String code) {
        var matcher = RECEIVE_CODE_DATE_PATTERN.matcher(nullToEmpty(code).trim());
        if (!matcher.matches()) {
            return "";
        }
        return matcher.group(1) + "-" + matcher.group(2) + "-" + matcher.group(3);
    }

    private String firstNonEmptyReceivedDate(List<CardRecord> cardRecords) {
        for (var cardRecord : cardRecords) {
            var value = cardRecord.getSpec() == null ? "" : nullToEmpty(cardRecord.getSpec().getReceivedAt()).trim();
            if (value.matches("^\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}.*")) {
                var parts = value.substring(0, Math.min(10, value.length())).replace("/", "-").split("-");
                return parts[0] + "-" + pad2(parts[1]) + "-" + pad2(parts[2]);
            }
        }
        return "";
    }

    private String firstNonEmptyReceivedAt(List<CardRecord> cardRecords) {
        for (var cardRecord : cardRecords) {
            if (cardRecord.getSpec() != null && !isBlank(cardRecord.getSpec().getReceivedAt())) {
                return cardRecord.getSpec().getReceivedAt().trim();
            }
        }
        return "";
    }

    private List<String> splitValues(String value) {
        return Arrays.stream(nullToEmpty(value).split("[,，、;；\\s]+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .distinct()
            .toList();
    }

    private LinkedHashSet<String> resourceNames(List<? extends run.halo.app.extension.Extension> extensions) {
        var names = new LinkedHashSet<String>();
        for (var extension : extensions) {
            names.add(resourceName(extension));
        }
        return names;
    }

    private String resourceName(run.halo.app.extension.Extension extension) {
        return extension == null || extension.getMetadata() == null || extension.getMetadata().getName() == null
            ? ""
            : extension.getMetadata().getName().trim();
    }

    private String normalize(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeResourceName(String value) {
        return nullToEmpty(value).trim().toUpperCase(Locale.ROOT);
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return isBlank(value) ? defaultValue : value.trim();
    }

    private String joinNonEmpty(String... values) {
        var joined = new ArrayList<String>();
        for (var value : values) {
            if (!isBlank(value)) {
                joined.add(value.trim());
            }
        }
        return String.join("；", joined);
    }

    private String pad2(String value) {
        var parsed = Integer.parseInt(value);
        return parsed < 10 ? "0" + parsed : String.valueOf(parsed);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private LinkedHashSet<String> SetOf(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    public record LegacyMigrationCommand(String mode, String confirmText) {
    }

    public record LegacyMigrationResult(
        String status,
        String message,
        String mode,
        long cardRecordTotal,
        long retainedCardRecords,
        long deletedStationCardPlaceholders,
        long deletedLegacyAutoReceiveCards,
        long updatedCardRecords,
        long receiveRecordsToCreate,
        long receiveRecordsSkipped,
        long matchedReceiveRecords,
        long unmatchedReceiveRecords,
        long offlineExchangeCardsToCreate,
        long offlineExchangeCardsSkipped,
        long systemSettingsToUpdate,
        long adjustedCardRecordSequence,
        long adjustedReceiveRecordSequence,
        List<String> warnings
    ) {
    }

    private record MigrationSnapshot(
        List<CardRecord> cardRecords,
        List<ReceiveRecord> receiveRecords,
        List<OfflineExchangeCard> offlineExchangeCards,
        List<SystemSetting> systemSettings
    ) {
    }

    private record MigrationPlan(
        LegacyMigrationResult result,
        List<ReceiveRecord> receiveRecordsToCreate,
        List<OfflineExchangeCard> offlineExchangeCardsToCreate,
        List<CardRecord> cardRecordsToUpdate,
        List<CardRecord> stationPlaceholdersToDelete,
        List<CardRecord> legacyAutoReceiveCardsToDelete,
        List<SystemSetting> systemSettingsToUpdate
    ) {
    }

    private record ReceiveGroup(
        CardRecord firstCard,
        List<CardRecord> linkedCards,
        List<CardRecord> autoCards,
        LinkedHashSet<String> remarks
    ) {
        ReceiveGroup(CardRecord firstCard) {
            this(firstCard, new ArrayList<>(), new ArrayList<>(), new LinkedHashSet<>());
        }
    }

    private record ReceiveBuildResult(
        List<ReceiveRecord> toCreate,
        long skippedExisting,
        long matchedCount,
        long unmatchedCount
    ) {
    }

    private record LinkedReceiveState(String receivedAt) {
    }

    private record OfflineBuildResult(
        List<OfflineExchangeCard> toCreate,
        long skippedExisting
    ) {
    }
}
