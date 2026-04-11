package run.halo.qsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslDataService {
    private static final Logger log = LoggerFactory.getLogger(QslDataService.class);

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final List<String> ADDRESS_RELATED_KEYS =
        List.of("name", "postcode", "address", "phone", "email");

    private final Map<String, AtomicLong> entityIdGenerators = new ConcurrentHashMap<>();
    private final AtomicLong auditIdGenerator = new AtomicLong(1000);
    private final AtomicBoolean loadingPersistentState = new AtomicBoolean(false);
    private final ThreadLocal<Boolean> suppressMailNotification = ThreadLocal.withInitial(() -> false);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final EmailNotifyService emailNotifyService;
    private final ReactiveExtensionClient extensionClient;

    private final Map<Long, Map<String, Object>> bureaus = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> equipments = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> antennas = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> powers = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> modes = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> qsoRecords = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> cardRecords = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> exchangeRequests = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> callsignBindings = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> addressBooks = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> importExportTasks = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> auditLogs = new ConcurrentHashMap<>();

    private final Map<String, Object> stationProfile = new ConcurrentHashMap<>();
    private final Map<String, Object> systemConfig = new ConcurrentHashMap<>();

    public QslDataService() {
        this.emailNotifyService = null;
        this.extensionClient = null;
        initDefaults();
    }

    @Autowired
    public QslDataService(EmailNotifyService emailNotifyService,
        ReactiveExtensionClient extensionClient) {
        this.emailNotifyService = emailNotifyService;
        this.extensionClient = extensionClient;
        initDefaults();
    }

    private void initDefaults() {
        systemConfig.put("queryLimitPerMin", 5);
        systemConfig.put("reissueEnabled", true);
        systemConfig.put("reissueIntervalDays", 7);
        systemConfig.put("requestNeedReview", true);
        systemConfig.put("hamRoleName", "ham");
        stationProfile.put("stationCallsign", "");
    }

    private void loadPersistentState() {
        if (extensionClient == null) {
            return;
        }
        loadingPersistentState.set(true);
        try {
            var state = extensionClient.fetch(QslPluginState.class, QslPluginState.STORAGE_NAME)
                .blockOptional()
                .orElse(null);
            if (state == null || state.getSpec() == null) {
                return;
            }
            var raw = Objects.toString(state.getSpec().getPayloadJson(), "{}");
            var payload = objectMapper.readValue(raw, MAP_TYPE);

            stationProfile.clear();
            stationProfile.put("stationCallsign", "");
            stationProfile.putAll(castMap(payload.get("stationProfile")));

            systemConfig.clear();
            initDefaults();
            systemConfig.putAll(castMap(payload.get("systemConfig")));

            restoreStore(bureaus, castMapList(payload.get("bureaus")));
            restoreStore(equipments, castMapList(payload.get("equipments")));
            restoreStore(antennas, castMapList(payload.get("antennas")));
            restoreStore(powers, castMapList(payload.get("powers")));
            restoreStore(modes, castMapList(payload.get("modes")));
            restoreStore(qsoRecords, castMapList(payload.get("qsoRecords")));
            restoreStore(cardRecords, castMapList(payload.get("cardRecords")));
            restoreStore(exchangeRequests, castMapList(payload.get("exchangeRequests")));
            restoreStore(callsignBindings, castMapList(payload.get("callsignBindings")));
            restoreStore(addressBooks, castMapList(payload.get("addressBooks")));
            restoreStore(importExportTasks, castMapList(payload.get("importExportTasks")));
            restoreStore(auditLogs, castMapList(payload.get("auditLogs")));

            entityIdGenerators.clear();
            var sequencesObj = payload.get("entitySequences");
            var sequences = new LinkedHashMap<String, Object>();
            if (sequencesObj instanceof Map<?, ?> map) {
                map.forEach((k, v) -> sequences.put(Objects.toString(k, ""), v));
            }
            sequences.forEach((k, v) -> entityIdGenerators.put(
                Objects.toString(k, "").toLowerCase(),
                new AtomicLong(Math.max(1000L, v == null ? 1000L : Long.parseLong(String.valueOf(v))))
            ));
            auditIdGenerator.set(Math.max(1000L,
                payload.get("auditSequence") == null
                    ? maxId(auditLogs)
                    : Long.parseLong(String.valueOf(payload.get("auditSequence")))));
        } catch (Exception ex) {
            log.warn("Failed to load persisted QSL state, fallback to in-memory defaults.", ex);
        } finally {
            loadingPersistentState.set(false);
        }
    }

    public void reloadFromPersistentStore() {
        loadPersistentState();
    }

    private void restoreStore(Map<Long, Map<String, Object>> target, List<Map<String, Object>> source) {
        target.clear();
        if (source == null) {
            return;
        }
        for (var item : source) {
            var id = asLong(item.get("id"));
            if (id == null) {
                continue;
            }
            target.put(id, new LinkedHashMap<>(item));
        }
    }

    private long maxId(Map<Long, Map<String, Object>> store) {
        return store.values().stream()
            .map(v -> asLong(v.get("id")))
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .max()
            .orElse(1000L);
    }

    private synchronized void persistState() {
        if (extensionClient == null || loadingPersistentState.get()) {
            return;
        }
        try {
            var state = buildStateSnapshot();
            var metadata = new Metadata();
            metadata.setName(QslPluginState.STORAGE_NAME);
            state.setMetadata(metadata);

            extensionClient.fetch(QslPluginState.class, QslPluginState.STORAGE_NAME)
                .flatMap(existing -> {
                    metadata.setVersion(existing.getMetadata().getVersion());
                    return extensionClient.update(state);
                })
                .switchIfEmpty(extensionClient.create(state))
                .block();
        } catch (Exception ex) {
            log.warn("Failed to persist QSL state.", ex);
        }
    }

    private QslPluginState buildStateSnapshot() {
        var state = new QslPluginState();
        var spec = new QslPluginState.Spec();
        try {
            spec.setPayloadJson(objectMapper.writeValueAsString(buildSnapshotPayload()));
        } catch (Exception ex) {
            throw new IllegalStateException("failed to serialize qsl state snapshot", ex);
        }
        state.setSpec(spec);
        return state;
    }

    private Map<String, Object> buildSnapshotPayload() {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("stationProfile", new LinkedHashMap<>(stationProfile));
        payload.put("systemConfig", new LinkedHashMap<>(systemConfig));
        payload.put("bureaus", snapshotStoreAllFields(bureaus));
        payload.put("equipments", snapshotStoreAllFields(equipments));
        payload.put("antennas", snapshotStoreAllFields(antennas));
        payload.put("powers", snapshotStoreAllFields(powers));
        payload.put("modes", snapshotStoreAllFields(modes));
        payload.put("qsoRecords", snapshotStoreAllFields(qsoRecords));
        payload.put("cardRecords", snapshotStoreAllFields(cardRecords));
        payload.put("exchangeRequests", snapshotStoreAllFields(exchangeRequests));
        payload.put("callsignBindings", snapshotStoreAllFields(callsignBindings));
        payload.put("addressBooks", snapshotStoreAllFields(addressBooks));
        payload.put("importExportTasks", snapshotStoreAllFields(importExportTasks));
        payload.put("auditLogs", snapshotStoreAllFields(auditLogs));
        payload.put("entitySequences", snapshotEntitySequences());
        payload.put("auditSequence", auditIdGenerator.get());
        return payload;
    }

    private Map<String, Long> snapshotEntitySequences() {
        var map = new LinkedHashMap<String, Long>();
        for (var key : List.of("bureau", "equipment", "antenna", "power", "mode", "qso",
            "card", "request", "binding", "address", "task", "audit")) {
            var seq = entityIdGenerators.computeIfAbsent(key, this::buildEntityGenerator).get();
            map.put(key, seq);
        }
        return map;
    }

    public Map<String, Object> getStationProfile() {
        return new LinkedHashMap<>(stationProfile);
    }

    public Map<String, Object> updateStationProfile(Map<String, Object> payload, String operator) {
        stationProfile.putAll(payload);
        writeAudit("station_profile", "station", "update", operator, "success", null, stationProfile);
        return getStationProfile();
    }

    public Map<String, Object> getSystemConfig() {
        return new LinkedHashMap<>(systemConfig);
    }

    public Map<String, Object> updateSystemConfig(Map<String, Object> payload, String operator) {
        systemConfig.putAll(payload);
        writeAudit("system_config", "config", "update", operator, "success", null, systemConfig);
        return getSystemConfig();
    }

    public List<Map<String, Object>> list(String type) {
        return getStore(type).values().stream()
            .filter(item -> !isDeleted(item))
            .sorted(Comparator.comparing(item -> ((Number) item.get("id")).longValue()))
            .map(item -> {
                var copy = new LinkedHashMap<>(item);
                if ("card".equals(type)) {
                    ensureCardStatusDefaults(copy);
                    hydrateAddressFields(copy);
                }
                if ("request".equals(type)) {
                    hydrateAddressFields(copy);
                }
                return copy;
            })
            .collect(Collectors.toList());
    }

    public Map<String, Object> get(String type, Long id) {
        var item = getStore(type).get(id);
        if (item == null) {
            return null;
        }
        var copy = new LinkedHashMap<>(item);
        if ("card".equals(type)) {
            ensureCardStatusDefaults(copy);
            hydrateAddressFields(copy);
        }
        if ("request".equals(type)) {
            hydrateAddressFields(copy);
        }
        return copy;
    }

    public Map<String, Object> create(String type, Map<String, Object> payload, String operator) {
        var now = nowString();
        var id = nextEntityId(type);
        var normalizedPayload = new LinkedHashMap<String, Object>(payload);
        normalizeAddressAssociation(type, normalizedPayload, null, operator);
        normalizeCardPrintCompat(type, normalizedPayload);
        var item = new LinkedHashMap<String, Object>();
        item.putAll(normalizedPayload);
        item.put("id", id);
        item.putIfAbsent("createdAt", now);
        item.put("updatedAt", now);
        item.putIfAbsent("createdBy", operator);
        item.put("updatedBy", operator);
        item.putIfAbsent("deleted", false);

        if ("card".equals(type)) {
            item.putIfAbsent("productionStatus", "DRAFT");
            item.putIfAbsent("sentStatus", "NOT_SENT");
            item.putIfAbsent("envelopePrinted", false);
            if (!item.containsKey("confirmStatus")) {
                var legacyReceived = Objects.toString(item.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                item.put("confirmStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "CONFIRMED" : "UNCONFIRMED");
            }
            if (!item.containsKey("returnCardStatus")) {
                var legacyReceived = Objects.toString(item.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                item.put("returnCardStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "RECEIVED" : "NOT_RECEIVED");
            }
            // Backward compatibility for old front-end fields.
            item.putIfAbsent("receivedStatus", "NOT_RECEIVED");
            var returnStatus = Objects.toString(item.getOrDefault("returnCardStatus", "NOT_RECEIVED"));
            item.put("receivedStatus", "RECEIVED".equalsIgnoreCase(returnStatus) ? "RECEIVED" : "NOT_RECEIVED");
            item.putIfAbsent("reissueCount", 0);
        }

        if ("address".equals(type)) {
            validateAddressBookUnique(null, normalizedPayload);
        }
        if ("binding".equals(type)) {
            validateBindingPayloadForCreate(normalizedPayload);
        }
        if ("card".equals(type)) {
            validateCardPayloadForCreate(normalizedPayload);
            overrideCardDateTimeByQso(normalizedPayload, null);
        }
        // Re-apply normalized payload after rule-based mutations (e.g. QSO datetime override).
        item.putAll(normalizedPayload);
        syncPeerLibraryFromLocalDictionary(type, item);

        getStore(type).put(id, item);
        writeAudit(type, String.valueOf(id), "create", operator, "success", null, item);
        if ("card".equals(type) && !isMailNotificationSuppressed()) {
            sendCardRecordedMail(item, Map.of());
            if (isCardPrepared(item)) {
                sendCardPreparedMail(item, Map.of());
            }
        }
        return new LinkedHashMap<>(item);
    }

    public Map<String, Object> update(String type, Long id, Map<String, Object> payload, String operator) {
        var store = getStore(type);
        var existing = store.get(id);
        if (existing == null || isDeleted(existing)) {
            return null;
        }
        var normalizedPayload = new LinkedHashMap<String, Object>(payload);
        normalizeAddressAssociation(type, normalizedPayload, existing, operator);
        normalizeCardPrintCompat(type, normalizedPayload);
        if ("address".equals(type)) {
            validateAddressBookUnique(id, normalizedPayload, existing);
        }
        if ("binding".equals(type)) {
            validateBindingPayloadForUpdate(id, normalizedPayload, existing);
        }
        if ("card".equals(type)) {
            validateCardPayloadForUpdate(id, normalizedPayload, existing);
            overrideCardDateTimeByQso(normalizedPayload, existing);
        }

        var before = new LinkedHashMap<>(existing);
        var beforePrepared = "card".equals(type) && isCardPrepared(before);
        existing.putAll(normalizedPayload);
        if ("card".equals(type)) {
            if (!normalizedPayload.containsKey("confirmStatus") && normalizedPayload.containsKey("receivedStatus")) {
                var legacyReceived = Objects.toString(normalizedPayload.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                existing.put("confirmStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "CONFIRMED" : "UNCONFIRMED");
            }
            if (!normalizedPayload.containsKey("returnCardStatus") && normalizedPayload.containsKey("receivedStatus")) {
                var legacyReceived = Objects.toString(normalizedPayload.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                existing.put("returnCardStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "RECEIVED" : "NOT_RECEIVED");
            }
            ensureCardStatusDefaults(existing);
        }
        existing.put("updatedAt", nowString());
        existing.put("updatedBy", operator);
        writeAudit(type, String.valueOf(id), "update", operator, "success", before, existing);
        if ("card".equals(type) && !isMailNotificationSuppressed()) {
            var afterPrepared = isCardPrepared(existing);
            if (!beforePrepared && afterPrepared) {
                sendCardPreparedMail(existing, Map.of());
            }
        }
        return new LinkedHashMap<>(existing);
    }

    public Map<String, Object> updateQsoAsAdmin(Long id, Map<String, Object> payload, String operator,
        Map<String, String> authHeaders) {
        if (!isAdminUser(operator, authHeaders)) {
            throw new IllegalArgumentException("only admin can edit qso records");
        }
        return update("qso", id, payload, operator);
    }

    public boolean softDelete(String type, Long id, String operator) {
        var store = getStore(type);
        var existing = store.get(id);
        if (existing == null || isDeleted(existing)) {
            return false;
        }
        var before = new LinkedHashMap<>(existing);
        existing.put("deleted", true);
        existing.put("deletedAt", nowString());
        existing.put("deletedBy", operator);
        existing.put("updatedAt", nowString());
        writeAudit(type, String.valueOf(id), "delete", operator, "success", before, existing);
        return true;
    }

    public Map<String, Object> sendConfirm(Map<String, Object> payload, String operator) {
        return sendConfirm(payload, operator, Map.of());
    }

    public Map<String, Object> sendConfirm(Map<String, Object> payload, String operator,
        Map<String, String> authHeaders) {
        var ids = castIds(payload.get("cardIds"));
        var updated = new ArrayList<Map<String, Object>>();
        var batchNo = Objects.toString(payload.getOrDefault("batchNo", "BATCH-" + System.currentTimeMillis()));
        var now = nowString();
        int mailSent = 0;
        int mailSkipped = 0;
        var mailErrors = new ArrayList<String>();
        for (Long id : ids) {
            var card = cardRecords.get(id);
            if (card == null || isDeleted(card)) {
                continue;
            }
            var before = new LinkedHashMap<>(card);
            card.put("productionStatus", "PRINTED");
            card.put("sentStatus", "SENT");
            card.put("printedAt", now);
            card.put("printedBy", operator);
            card.put("sentAt", now);
            card.put("sentBy", operator);
            card.put("sentBatchNo", batchNo);
            card.put("updatedAt", now);
            card.put("updatedBy", operator);
            if (Boolean.TRUE.equals(payload.get("isReissue"))) {
                var count = ((Number) card.getOrDefault("reissueCount", 0)).intValue();
                card.put("reissueCount", count + 1);
            }
            writeAudit("card", String.valueOf(id), "send_confirm", operator, "success", before, card);
            var updatedCard = new LinkedHashMap<>(card);
            var mailResult = sendCardSendConfirmMail(updatedCard, authHeaders);
            if (Boolean.TRUE.equals(mailResult.get("mailSent"))) {
                mailSent++;
            } else if (Boolean.TRUE.equals(mailResult.get("mailSkipped"))) {
                mailSkipped++;
            } else if (mailResult.get("mailError") != null) {
                mailErrors.add("card#" + id + ": " + Objects.toString(mailResult.get("mailError"), ""));
            }
            updated.add(updatedCard);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("count", updated.size());
        result.put("items", updated);
        result.put("mailSentCount", mailSent);
        result.put("mailSkippedCount", mailSkipped);
        result.put("mailErrorCount", mailErrors.size());
        result.put("mailErrors", mailErrors);
        return result;
    }

    public Map<String, Object> receiveConfirm(Map<String, Object> payload, String operator) {
        return receiveConfirm(payload, operator, Map.of());
    }

    public Map<String, Object> receiveConfirm(Map<String, Object> payload, String operator,
        Map<String, String> authHeaders) {
        var callsign = Objects.toString(payload.getOrDefault("callsign", "")).trim();
        if (!callsign.isBlank()) {
            return receiveConfirmByCallsign(payload, operator, authHeaders);
        }
        var ids = castIds(payload.get("cardIds"));
        var updated = new ArrayList<Map<String, Object>>();
        var now = nowString();
        int mailSent = 0;
        int mailSkipped = 0;
        var mailErrors = new ArrayList<String>();
        for (Long id : ids) {
            var card = cardRecords.get(id);
            if (card == null || isDeleted(card)) {
                continue;
            }
            var before = new LinkedHashMap<>(card);
            ensureCardStatusDefaults(card);
            card.put("returnCardStatus", "RECEIVED");
            card.put("returnedAt", now);
            card.put("returnedBy", operator);
            card.put("receivedStatus", "RECEIVED");
            card.put("receivedAt", now);
            card.put("receivedBy", operator);
            if (payload.containsKey("receiveRemark")) {
                card.put("receiveRemark", payload.get("receiveRemark"));
            }
            card.put("updatedAt", now);
            card.put("updatedBy", operator);
            writeAudit("card", String.valueOf(id), "receive_confirm", operator, "success", before, card);
            var updatedCard = new LinkedHashMap<>(card);
            var mailResult = sendCardReceiveConfirmMail(updatedCard, authHeaders);
            if (Boolean.TRUE.equals(mailResult.get("mailSent"))) {
                mailSent++;
            } else if (Boolean.TRUE.equals(mailResult.get("mailSkipped"))) {
                mailSkipped++;
            } else if (mailResult.get("mailError") != null) {
                mailErrors.add("card#" + id + ": " + Objects.toString(mailResult.get("mailError"), ""));
            }
            updated.add(updatedCard);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("count", updated.size());
        result.put("items", updated);
        result.put("mailSentCount", mailSent);
        result.put("mailSkippedCount", mailSkipped);
        result.put("mailErrorCount", mailErrors.size());
        result.put("mailErrors", mailErrors);
        return result;
    }

    private Map<String, Object> receiveConfirmByCallsign(Map<String, Object> payload, String operator,
        Map<String, String> authHeaders) {
        var callsign = Objects.toString(payload.getOrDefault("callsign", "")).trim();
        if (callsign.isBlank()) {
            throw new IllegalArgumentException("callsign is required");
        }
        var matchedEyeball = list("card").stream()
            .filter(c -> "EYEBALL".equalsIgnoreCase(Objects.toString(c.getOrDefault("cardType", ""))))
            .filter(c -> callsign.equalsIgnoreCase(Objects.toString(c.getOrDefault("peerCallsign", "")).trim()))
            .max(Comparator.comparingLong(c -> ((Number) c.get("id")).longValue()))
            .orElse(null);

        if (matchedEyeball == null) {
            var missing = new ArrayList<String>();
            checkRequired(payload, "name", "姓名", missing);
            checkRequired(payload, "address", "地址", missing);
            checkRequired(payload, "postcode", "邮编", missing);
            checkRequired(payload, "phone", "电话", missing);
            checkRequired(payload, "email", "电子邮箱", missing);
            if (!missing.isEmpty()) {
                throw new IllegalArgumentException("missing recipient fields: " + String.join("、", missing));
            }

            var now = nowOffsetDateTime();
            var createPayload = new LinkedHashMap<String, Object>();
            createPayload.put("cardType", "EYEBALL");
            createPayload.put("peerCallsign", callsign);
            createPayload.put("name", Objects.toString(payload.get("name"), "").trim());
            createPayload.put("address", Objects.toString(payload.get("address"), "").trim());
            createPayload.put("postcode", Objects.toString(payload.get("postcode"), "").trim());
            createPayload.put("phone", Objects.toString(payload.get("phone"), "").trim());
            createPayload.put("email", Objects.toString(payload.get("email"), "").trim());
            createPayload.put("cardDate", now.toLocalDate().toString());
            createPayload.put("cardTime", now.toLocalTime().toString());
            createPayload.put("timezone", "UTC+8");
            createPayload.put("productionStatus", "DRAFT");
            createPayload.put("sentStatus", "NOT_SENT");
            createPayload.put("confirmStatus", "UNCONFIRMED");
            createPayload.put("returnCardStatus", "RECEIVED");
            createPayload.put("receivedStatus", "RECEIVED");
            createPayload.put("returnedAt", now.toString());
            createPayload.put("returnedBy", operator);
            createPayload.put("receiveRemark", Objects.toString(payload.getOrDefault("receiveRemark", ""), ""));
            var created = create("card", createPayload, operator);
            return Map.of("count", 1, "created", true, "items", List.of(created));
        }

        var id = Long.parseLong(String.valueOf(matchedEyeball.get("id")));
        return receiveConfirm(Map.of(
            "cardIds", List.of(id),
            "receiveRemark", Objects.toString(payload.getOrDefault("receiveRemark", ""))
        ), operator, authHeaders);
    }

    private void checkRequired(Map<String, Object> payload, String key, String label, List<String> missing) {
        var value = Objects.toString(payload.getOrDefault(key, ""), "").trim();
        if (value.isBlank()) {
            missing.add(label);
        }
    }

    public Map<String, Object> confirmByPeer(Map<String, Object> payload, String operator) {
        var ids = castIds(payload.get("cardIds"));
        var updated = new ArrayList<Map<String, Object>>();
        var now = nowString();
        for (Long id : ids) {
            var card = cardRecords.get(id);
            if (card == null || isDeleted(card)) {
                continue;
            }
            var before = new LinkedHashMap<>(card);
            ensureCardStatusDefaults(card);
            card.put("confirmStatus", "CONFIRMED");
            card.put("confirmedAt", now);
            card.put("confirmedBy", operator);
            if (payload.containsKey("confirmRemark")) {
                card.put("confirmRemark", payload.get("confirmRemark"));
            }
            card.put("updatedAt", now);
            card.put("updatedBy", operator);
            writeAudit("card", String.valueOf(id), "peer_receive_confirm", operator, "success", before, card);
            updated.add(new LinkedHashMap<>(card));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("count", updated.size());
        result.put("items", updated);
        return result;
    }

    public Map<String, Object> reissuePrepare(Map<String, Object> payload, String operator) {
        var cardId = Long.parseLong(String.valueOf(payload.get("cardId")));
        var card = cardRecords.get(cardId);
        if (card == null || isDeleted(card)) {
            return Map.of("prepared", false, "reason", "card_not_found");
        }
        var before = new LinkedHashMap<>(card);
        card.put("productionStatus", "PENDING_PRINT");
        card.put("sentStatus", "NOT_SENT");
        card.remove("printedAt");
        card.remove("printedBy");
        card.remove("sentAt");
        card.remove("sentBy");
        card.remove("sentBatchNo");
        card.put("updatedAt", nowString());
        card.put("updatedBy", operator);
        writeAudit("card", String.valueOf(cardId), "reissue_prepare", operator, "success", before, card);
        return Map.of("prepared", true, "card", new LinkedHashMap<>(card));
    }

    public byte[] exportCardsCsv(List<Long> cardIds) {
        var rows = cardIds == null || cardIds.isEmpty()
            ? list("card") : cardIds.stream().map(cardRecords::get).filter(Objects::nonNull).toList();
        var header = "呼号,日期,时间,时区UTC,时区UTC+8,卡片类型QSO,卡片类型SWL,卡片类型EYEBALL,频率,本台设备,模式FM,模式CW,模式SSB,模式自定义,模式,本台功率,本台给对方的信号报告,本台天线,发出卡片,回复卡片,备注\n";
        var builder = new StringBuilder(header);
        for (var r : rows) {
            ensureCardStatusDefaults(r);
            var qso = getRelatedQso(r);
            var qsoDate = firstNonBlank(
                Objects.toString(r.getOrDefault("cardDate", ""), ""),
                Objects.toString(qso.getOrDefault("qsoDate", ""), "")
            );
            var qsoTime = firstNonBlank(
                Objects.toString(r.getOrDefault("cardTime", ""), ""),
                Objects.toString(qso.getOrDefault("qsoTime", ""), "")
            );
            var timezone = firstNonBlank(
                Objects.toString(r.getOrDefault("timezone", ""), ""),
                Objects.toString(qso.getOrDefault("timezone", ""), "")
            );
            var equipmentName = firstNonBlank(
                Objects.toString(r.getOrDefault("equipmentName", ""), ""),
                getNameById(equipments, asLong(qso.get("equipmentId")))
            );
            var antennaName = firstNonBlank(
                Objects.toString(r.getOrDefault("antennaName", ""), ""),
                getNameById(antennas, asLong(qso.get("antennaId")))
            );
            var powerName = firstNonBlank(
                Objects.toString(r.getOrDefault("powerText", ""), ""),
                getNameById(powers, asLong(qso.get("powerPresetId")))
            );
            var remark = firstNonBlank(
                Objects.toString(r.getOrDefault("remark", ""), ""),
                Objects.toString(qso.getOrDefault("remark", ""), "")
            );
            var mode = firstNonBlank(
                Objects.toString(r.getOrDefault("mode", ""), ""),
                Objects.toString(qso.getOrDefault("mode", ""), "")
            ).trim().toUpperCase();
            var timezoneUpper = timezone.trim().toUpperCase();
            var cardType = normalizeCardType(r.get("cardType"));
            var received = "CONFIRMED".equalsIgnoreCase(Objects.toString(r.getOrDefault("confirmStatus", "")))
                || "RECEIVED".equalsIgnoreCase(Objects.toString(r.getOrDefault("returnCardStatus", "")));
            var sent = "SENT".equalsIgnoreCase(Objects.toString(r.getOrDefault("sentStatus", "")));

            builder.append(csv(r.get("peerCallsign"))).append(',')
                .append(csv(qsoDate)).append(',')
                .append(csv(qsoTime)).append(',')
                .append(csv("UTC".equals(timezoneUpper) ? "1" : "0")).append(',')
                .append(csv("UTC+8".equals(timezoneUpper) ? "1" : "0")).append(',')
                .append(csv("QSO".equals(cardType) ? "1" : "0")).append(',')
                .append(csv("LISTEN".equals(cardType) ? "1" : "0")).append(',')
                .append(csv("EYEBALL".equals(cardType) ? "1" : "0")).append(',')
                .append(csv(qso.get("frequency"))).append(',')
                .append(csv(equipmentName)).append(',')
                .append(csv("FM".equals(mode) ? "1" : "0")).append(',')
                .append(csv("CW".equals(mode) ? "1" : "0")).append(',')
                .append(csv("SSB".equals(mode) ? "1" : "0")).append(',')
                .append(csv((!"FM".equals(mode) && !"CW".equals(mode) && !"SSB".equals(mode) && !mode.isBlank()) ? "1" : "0")).append(',')
                .append(csv(mode)).append(',')
                .append(csv(powerName)).append(',')
                .append(csv(firstNonBlank(
                    Objects.toString(r.getOrDefault("rstSent", ""), ""),
                    Objects.toString(qso.getOrDefault("rstSent", ""), "")))).append(',')
                .append(csv(antennaName)).append(',')
                .append(csv(sent ? "1" : "0")).append(',')
                .append(csv(received ? "1" : "0")).append(',')
                .append(csv(remark)).append('\n');
        }
        return withBom(builder.toString());
    }

    public byte[] exportEnvelopesCsv(List<Long> cardIds) {
        var rows = cardIds == null || cardIds.isEmpty()
            ? list("card") : cardIds.stream().map(cardRecords::get).filter(Objects::nonNull).toList();
        var header = "呼号,姓名,电话,邮编,收件地址\n";
        var builder = new StringBuilder(header);
        var grouped = new LinkedHashMap<String, Map<String, Object>>();
        for (var r : rows) {
            hydrateAddressFields(r);
            var addressRaw = Objects.toString(r.getOrDefault("address", ""), "").trim();
            if (addressRaw.isBlank()) {
                continue;
            }
            var key = normalize(addressRaw);
            var envelope = grouped.computeIfAbsent(key, k -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("address", addressRaw);
                item.put("phone", "");
                item.put("postcode", "");
                item.put("callsigns", new ArrayList<String>());
                return item;
            });
            var callsigns = castStringList(envelope.get("callsigns"));
            var callsign = Objects.toString(r.getOrDefault("peerCallsign", ""), "").trim();
            if (!callsign.isBlank() && !callsigns.contains(callsign)) {
                callsigns.add(callsign);
            }
            if (Objects.toString(envelope.getOrDefault("phone", ""), "").isBlank()) {
                envelope.put("phone", Objects.toString(r.getOrDefault("phone", ""), "").trim());
            }
            if (Objects.toString(envelope.getOrDefault("postcode", ""), "").isBlank()) {
                envelope.put("postcode", Objects.toString(r.getOrDefault("postcode", ""), "").trim());
            }
        }

        for (var envelope : grouped.values()) {
            var callsigns = castStringList(envelope.get("callsigns"));
            callsigns.sort(String::compareToIgnoreCase);
            var receiver = String.join("、", callsigns);
            builder.append(csv(receiver)).append(',')
                .append(csv(receiver)).append(',')
                .append(csv(envelope.get("phone"))).append(',')
                .append(csv(envelope.get("postcode"))).append(',')
                .append(csv(envelope.get("address"))).append('\n');
        }
        return withBom(builder.toString());
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return (List<String>) list;
        }
        return new ArrayList<>();
    }

    public Map<String, Object> backupExport(String operator) {
        var task = create("task", Map.of("taskType", "EXPORT", "status", "COMPLETED", "fileName", "backup.json"), operator);
        return Map.of("task", task, "summary", "backup export simulated");
    }

    public byte[] exportFullBackupJson(String operator) {
        var timestamp = System.currentTimeMillis();
        var fileName = "qsl-full-backup-" + timestamp + ".json";
        var task = create("task",
            Map.of("taskType", "EXPORT", "status", "COMPLETED", "fileName", fileName,
                "summary", "full backup export"),
            operator);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("meta", Map.of(
            "format", "qsl-full-backup",
            "version", 1,
            "exportedAt", nowString(),
            "operator", Objects.toString(operator, ""),
            "taskId", task.get("id"),
            "taskFileName", fileName
        ));
        payload.put("stationProfile", new LinkedHashMap<>(stationProfile));
        payload.put("systemConfig", new LinkedHashMap<>(systemConfig));
        payload.put("bureauConfigs", snapshotStoreAllFields(bureaus));
        payload.put("equipments", snapshotStoreAllFields(equipments));
        payload.put("antennas", snapshotStoreAllFields(antennas));
        payload.put("powerPresets", snapshotStoreAllFields(powers));
        payload.put("modes", snapshotStoreAllFields(modes));
        payload.put("qsoRecords", snapshotStoreAllFields(qsoRecords));
        payload.put("qslCardRecords", snapshotStoreAllFields(cardRecords));
        payload.put("exchangeRequests", snapshotStoreAllFields(exchangeRequests));
        payload.put("callsignBindings", snapshotStoreAllFields(callsignBindings));
        payload.put("addressBooks", snapshotStoreAllFields(addressBooks));
        payload.put("importExportTasks", snapshotStoreAllFields(importExportTasks));
        payload.put("auditLogs", snapshotStoreAllFields(auditLogs));
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("failed to export full backup", ex);
        }
    }

    public Map<String, Object> backupImport(String operator) {
        var task = create("task", Map.of("taskType", "IMPORT", "status", "COMPLETED", "fileName", "backup.json"), operator);
        return Map.of("task", task, "summary", "backup import simulated");
    }

    public Map<String, Object> importBackupFile(String fileName, byte[] bytes, String dataset, String operator) throws Exception {
        var name = Objects.toString(fileName, "").trim();
        if (name.isBlank()) {
            throw new IllegalArgumentException("file is required");
        }
        var payload = parseImportPayload(name, bytes, dataset);
        var result = importBackupData(payload, operator);
        var task = castMap(result.get("task"));
        if (task != null) {
            update("task", asLong(task.get("id")),
                Map.of("fileName", name, "summary", "file import: " + name), operator);
            result = new LinkedHashMap<>(result);
            result.put("task", get("task", asLong(task.get("id"))));
        }
        return result;
    }

    public Map<String, Object> approveRequest(Long id, String operator) {
        return approveRequest(id, operator, Map.of());
    }

    public Map<String, Object> approveRequest(Long id, String operator, Map<String, String> authHeaders) {
        var request = exchangeRequests.get(id);
        if (request == null || isDeleted(request)) {
            return null;
        }
        var now = nowString();
        var requestType = Objects.toString(request.getOrDefault("requestType", "NORMAL"));
        if ("NORMAL".equalsIgnoreCase(requestType)) {
            var createPayload = new LinkedHashMap<String, Object>();
            createPayload.put("cardType", "EYEBALL");
            createPayload.put("peerCallsign", request.getOrDefault("bindCallsign", ""));
            var requestAddressId = asLong(request.get("addressId"));
            if (requestAddressId != null) {
                createPayload.put("addressId", requestAddressId);
            } else {
                // Compatibility for legacy records that still store address inline.
                createPayload.put("name", request.getOrDefault("name", ""));
                createPayload.put("postcode", request.getOrDefault("postcode", ""));
                createPayload.put("address", request.getOrDefault("address", ""));
                createPayload.put("phone", request.getOrDefault("phone", ""));
                createPayload.put("email", request.getOrDefault("email", ""));
            }
            createPayload.put("productionStatus", "PENDING_PRINT");
            createPayload.put("sentStatus", "NOT_SENT");
            createPayload.put("confirmStatus", "UNCONFIRMED");
            createPayload.put("returnCardStatus", "NOT_RECEIVED");
            createPayload.put("cardDate", nowDateString());
            createPayload.put("cardTime", nowTimeString());
            createPayload.put("timezone", "UTC+8");
            createPayload.put("remark", "request-approved");
            // 场景拆分：换卡审核通过自动建卡不触发“卡片记录已登记”邮件，仅发送审核结果邮件。
            var generated = executeWithoutMailNotification(() -> create("card", createPayload, operator));
            request.put("generatedCardId", generated.get("id"));
        } else if ("REISSUE".equalsIgnoreCase(requestType)) {
            var cardIdObj = request.get("qslCardRecordId");
            if (cardIdObj != null) {
                var card = cardRecords.get(Long.parseLong(String.valueOf(cardIdObj)));
                if (card != null && !isDeleted(card)) {
                    var before = new LinkedHashMap<>(card);
                    card.put("productionStatus", "PENDING_PRINT");
                    card.put("sentStatus", "NOT_SENT");
                    card.remove("printedAt");
                    card.remove("printedBy");
                    card.remove("sentAt");
                    card.remove("sentBy");
                    card.remove("sentBatchNo");
                    card.put("updatedAt", now);
                    card.put("updatedBy", operator);
                    writeAudit("card", String.valueOf(card.get("id")), "reissue_prepare_by_approval", operator,
                        "success", before, card);
                }
            }
        }
        var updated = update("request", id, Map.of("status", "APPROVED", "reviewedBy", operator, "reviewedAt", now), operator);
        if (updated == null) {
            return null;
        }
        var mailResult = sendReviewMail(updated, true, stationProfileRemarkForApprovedReview(), now, authHeaders);
        if (Boolean.TRUE.equals(mailResult.get("mailSent"))) {
            updated = update("request", id, Map.of("mailSentAt", nowString()), operator);
        } else {
            updated = update("request", id,
                Map.of("mailError", Objects.toString(mailResult.getOrDefault("mailError", "unknown"), "")), operator);
        }
        var result = new LinkedHashMap<String, Object>();
        result.putAll(updated == null ? Map.of() : updated);
        result.putAll(mailResult);
        return result;
    }

    public Map<String, Object> rejectRequest(Long id, String reason, String operator) {
        return rejectRequest(id, reason, operator, Map.of());
    }

    public Map<String, Object> rejectRequest(Long id, String reason, String operator, Map<String, String> authHeaders) {
        var updated = update("request", id,
            Map.of("status", "REJECTED", "reviewReason", reason, "reviewedBy", operator,
                "reviewedAt", nowString()),
            operator);
        if (updated == null) {
            return null;
        }
        var mailResult = sendReviewMail(updated, false, reason, Objects.toString(updated.get("reviewedAt"), ""), authHeaders);
        if (Boolean.TRUE.equals(mailResult.get("mailSent"))) {
            updated = update("request", id, Map.of("mailSentAt", nowString()), operator);
        } else {
            updated = update("request", id,
                Map.of("mailError", Objects.toString(mailResult.getOrDefault("mailError", "unknown"), "")), operator);
        }
        var result = new LinkedHashMap<String, Object>();
        result.putAll(updated == null ? Map.of() : updated);
        result.putAll(mailResult);
        return result;
    }

    public Map<String, Object> approveBinding(Long id, String operator) {
        return approveBinding(id, operator, Map.of());
    }

    public Map<String, Object> approveBinding(Long id, String operator, Map<String, String> authHeaders) {
        var binding = get("binding", id);
        if (binding == null) {
            return null;
        }
        var userName = Objects.toString(binding.getOrDefault("userId", ""), "").trim();
        if (userName.isBlank()) {
            throw new IllegalArgumentException("binding userId is required for role sync");
        }
        syncGrantHamRole(userName, authHeaders);
        return update("binding", id,
            Map.of("status", "APPROVED", "reviewedBy", operator, "reviewedAt", nowString(), "roleSynced", true),
            operator);
    }

    public List<Map<String, Object>> listRoleOptions(Map<String, String> authHeaders) {
        var result = new ArrayList<Map<String, Object>>();
        var page = 1;
        while (true) {
            var path = "/api/v1alpha1/roles?page=" + page + "&size=500";
            var root = requestHaloApi("GET", path, null, authHeaders);
            var items = root.path("items");
            if (items.isArray()) {
                for (var item : items) {
                    var metadata = item.path("metadata");
                    var name = metadata.path("name").asText("").trim();
                    if (name.isBlank()) {
                        continue;
                    }
                    if (!shouldExposeRoleForHamGrant(item)) {
                        continue;
                    }
                    var annotations = metadata.path("annotations");
                    var displayName = annotations.path("rbac.authorization.halo.run/display-name").asText("").trim();
                    if (displayName.isBlank()) {
                        displayName = item.path("spec").path("displayName").asText("").trim();
                    }
                    if (displayName.isBlank()) {
                        displayName = name;
                    }
                    result.add(Map.of("name", name, "displayName", displayName));
                }
            }
            if (root.path("hasNext").asBoolean(false)) {
                page++;
                continue;
            }
            break;
        }
        result.sort((a, b) -> {
            var an = Objects.toString(a.get("name"), "");
            var bn = Objects.toString(b.get("name"), "");
            if ("ham".equalsIgnoreCase(an) && !"ham".equalsIgnoreCase(bn)) {
                return -1;
            }
            if (!"ham".equalsIgnoreCase(an) && "ham".equalsIgnoreCase(bn)) {
                return 1;
            }
            return Objects.toString(a.get("displayName"), "")
                .compareToIgnoreCase(Objects.toString(b.get("displayName"), ""));
        });
        return result;
    }

    private boolean shouldExposeRoleForHamGrant(JsonNode roleItem) {
        var metadata = roleItem.path("metadata");
        var labels = metadata.path("labels");
        var name = metadata.path("name").asText("").trim();
        if (name.isBlank()) {
            return false;
        }
        if ("ham".equalsIgnoreCase(name)) {
            return true;
        }
        if (name.startsWith("qsl-management-role-")) {
            return false;
        }
        if ("true".equalsIgnoreCase(labels.path("rbac.authorization.halo.run/system-reserved").asText(""))) {
            return false;
        }
        if ("true".equalsIgnoreCase(labels.path("halo.run/role-template").asText(""))) {
            return false;
        }
        var pluginName = labels.path("plugin.halo.run/plugin-name").asText("").trim();
        return pluginName.isBlank();
    }

    public Map<String, Object> unbindBinding(Long id, String operator) {
        return unbindBinding(id, operator, Map.of());
    }

    public Map<String, Object> unbindBinding(Long id, String operator, Map<String, String> authHeaders) {
        var existing = get("binding", id);
        if (existing == null) {
            return null;
        }
        var userName = Objects.toString(existing.getOrDefault("userId", ""), "").trim();
        var status = Objects.toString(existing.getOrDefault("status", ""), "").trim().toUpperCase();
        if ("UNBOUND".equals(status)) {
            return existing;
        }
        if (!"APPROVED".equals(status)) {
            throw new IllegalArgumentException("only approved binding can be unbound");
        }
        var updated = update("binding", id,
            Map.of("status", "UNBOUND", "unboundBy", operator, "unboundAt", nowString()),
            operator);
        if (updated != null && !hasApprovedBindingForUser(userName)) {
            syncRevokeHamRole(userName, authHeaders);
        }
        return updated;
    }

    private Map<String, Object> sendReviewMail(Map<String, Object> request, boolean approved, String reason,
        String reviewedAt,
        Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        var merged = new LinkedHashMap<>(request);
        hydrateAddressFields(merged);
        return emailNotifyService.notifyExchangeRequestReviewed(
            Objects.toString(merged.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(merged.getOrDefault("bindCallsign", "")),
            approved,
            reason,
            reviewedAt,
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private Map<String, Object> sendCardSendConfirmMail(Map<String, Object> card, Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        var merged = new LinkedHashMap<>(card);
        hydrateAddressFields(merged);
        return emailNotifyService.notifyCardSendConfirmed(
            Objects.toString(merged.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(merged.getOrDefault("peerCallsign", "")),
            Objects.toString(merged.getOrDefault("id", "")),
            Objects.toString(merged.getOrDefault("sentAt", "")),
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private Map<String, Object> sendCardReceiveConfirmMail(Map<String, Object> card, Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        var merged = new LinkedHashMap<>(card);
        hydrateAddressFields(merged);
        return emailNotifyService.notifyCardReceiveConfirmed(
            Objects.toString(merged.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(merged.getOrDefault("peerCallsign", "")),
            Objects.toString(merged.getOrDefault("id", "")),
            Objects.toString(merged.getOrDefault("receivedAt", merged.getOrDefault("returnedAt", ""))),
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private Map<String, Object> sendCardRecordedMail(Map<String, Object> card, Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        var merged = new LinkedHashMap<>(card);
        hydrateAddressFields(merged);
        return emailNotifyService.notifyCardRecorded(
            Objects.toString(merged.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(merged.getOrDefault("peerCallsign", "")),
            Objects.toString(merged.getOrDefault("id", "")),
            Objects.toString(merged.getOrDefault("cardType", "")),
            Objects.toString(merged.getOrDefault("createdAt", nowString())),
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private Map<String, Object> sendCardPreparedMail(Map<String, Object> card, Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        var merged = new LinkedHashMap<>(card);
        hydrateAddressFields(merged);
        var preparedAt = firstNonBlank(
            Objects.toString(merged.getOrDefault("envelopePrintedAt", ""), ""),
            Objects.toString(merged.getOrDefault("printedAt", ""), ""),
            Objects.toString(merged.getOrDefault("updatedAt", ""), "")
        );
        return emailNotifyService.notifyCardPrepared(
            Objects.toString(merged.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(merged.getOrDefault("peerCallsign", "")),
            Objects.toString(merged.getOrDefault("id", "")),
            preparedAt,
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private boolean isCardPrepared(Map<String, Object> card) {
        if (card == null) {
            return false;
        }
        var production = Objects.toString(card.getOrDefault("productionStatus", ""), "").trim().toUpperCase();
        var envelopePrinted = Boolean.TRUE.equals(card.get("envelopePrinted"));
        return "PRINTED".equals(production) && envelopePrinted;
    }

    private String stationCallsignForMail() {
        var value = Objects.toString(stationProfile.getOrDefault("stationCallsign", ""), "").trim();
        return value.isBlank() ? "QSL" : value;
    }

    private String stationProfileRemarkForApprovedReview() {
        var postcode = Objects.toString(stationProfile.getOrDefault("postcode", ""), "").trim();
        var address = Objects.toString(stationProfile.getOrDefault("address", ""), "").trim();
        var phone = Objects.toString(stationProfile.getOrDefault("phone", ""), "").trim();
        var name = Objects.toString(stationProfile.getOrDefault("name", ""), "").trim();
        var stationCallsign = stationCallsignForMail();
        var remark = Objects.toString(stationProfile.getOrDefault("remark", ""), "").trim();

        var receiver = (name + "（" + stationCallsign + "）收").trim();
        var parts = new ArrayList<String>();
        if (!postcode.isBlank()) {
            parts.add(postcode);
        }
        if (!address.isBlank()) {
            parts.add(address);
        }
        if (!phone.isBlank()) {
            parts.add(phone);
        }
        if (!receiver.isBlank()) {
            parts.add(receiver);
        }
        if (!remark.isBlank()) {
            parts.add(remark);
        }
        return String.join("，", parts);
    }

    public Map<String, Object> rejectBinding(Long id, String reason, String operator) {
        return update("binding", id,
            Map.of("status", "REJECTED", "reviewReason", reason, "reviewedBy", operator,
                "reviewedAt", nowString()),
            operator);
    }

    public List<Map<String, Object>> listBindingsByUser(String userId) {
        var key = Objects.toString(userId, "").trim();
        if (key.isBlank()) {
            return List.of();
        }
        return list("binding").stream()
            .filter(b -> key.equals(Objects.toString(b.getOrDefault("userId", ""), "").trim()))
            .sorted(Comparator.comparing(item -> ((Number) item.get("id")).longValue()))
            .toList();
    }

    public Map<String, Object> submitBinding(String userId, Map<String, Object> payload, String operator) {
        var userKey = Objects.toString(userId, "").trim();
        if (userKey.isBlank()) {
            throw new IllegalArgumentException("userId is required");
        }
        var normalized = new LinkedHashMap<String, Object>(payload == null ? Map.of() : payload);
        var callsign = Objects.toString(normalized.getOrDefault("callsign", ""), "").trim().toUpperCase();
        if (callsign.isBlank()) {
            throw new IllegalArgumentException("callsign is required");
        }
        validateBindingProof(normalized, null);
        var duplicated = listBindingsByUser(userKey).stream()
            .filter(b -> callsign.equalsIgnoreCase(Objects.toString(b.getOrDefault("callsign", ""), "")))
            .filter(b -> {
                var status = Objects.toString(b.getOrDefault("status", "PENDING"), "").trim().toUpperCase();
                return !"REJECTED".equals(status) && !"UNBOUND".equals(status);
            })
            .findFirst();
        if (duplicated.isPresent()) {
            throw new IllegalArgumentException("该呼号已有未完成或已通过的绑定申请");
        }
        normalized.put("callsign", callsign);
        normalized.put("userId", userKey);
        normalized.put("status", "PENDING");
        normalized.put("reviewReason", "");
        normalized.put("reviewedBy", "");
        normalized.put("reviewedAt", "");
        normalized.put("verifyMethod", resolveVerifyMethod(normalized, null));
        return create("binding", normalized, operator);
    }

    public Map<String, Object> searchCallsignRecordStats(String callsign) {
        var keyword = Objects.toString(callsign, "").trim();
        if (keyword.isBlank()) {
            return Map.of("callsign", "", "qsoCount", 0, "cardCount", 0, "hasRecords", false);
        }
        var qsoCount = list("qso").stream()
            .filter(q -> keyword.equalsIgnoreCase(Objects.toString(q.getOrDefault("peerCallsign", ""), "").trim()))
            .count();
        var cardCount = list("card").stream()
            .filter(c -> keyword.equalsIgnoreCase(Objects.toString(c.getOrDefault("peerCallsign", ""), "").trim()))
            .count();
        return Map.of(
            "callsign", keyword.toUpperCase(),
            "qsoCount", qsoCount,
            "cardCount", cardCount,
            "hasRecords", (qsoCount + cardCount) > 0
        );
    }

    public List<Map<String, Object>> listAddressesByBoundUser(String userId, String callsign) {
        var approved = approvedCallsignsOfUser(userId);
        if (approved.isEmpty()) {
            return List.of();
        }
        var target = Objects.toString(callsign, "").trim().toUpperCase();
        return list("address").stream()
            .filter(a -> {
                var c = Objects.toString(a.getOrDefault("callsign", ""), "").trim().toUpperCase();
                return approved.contains(c) && (target.isBlank() || target.equals(c));
            })
            .toList();
    }

    public Map<String, Object> createAddressByBoundUser(String userId, Map<String, Object> payload, String operator) {
        var callsign = Objects.toString(payload.getOrDefault("callsign", ""), "").trim().toUpperCase();
        assertBoundCallsign(userId, callsign);
        var normalized = new LinkedHashMap<String, Object>(payload);
        normalized.put("callsign", callsign);
        return create("address", normalized, operator);
    }

    public Map<String, Object> updateAddressByBoundUser(String userId, Long id, Map<String, Object> payload,
        String operator) {
        var existing = get("address", id);
        if (existing == null) {
            return null;
        }
        var existingCallsign = Objects.toString(existing.getOrDefault("callsign", ""), "").trim().toUpperCase();
        assertBoundCallsign(userId, existingCallsign);
        var normalized = new LinkedHashMap<String, Object>(payload);
        var nextCallsign = payload.containsKey("callsign")
            ? Objects.toString(payload.getOrDefault("callsign", ""), "").trim().toUpperCase()
            : existingCallsign;
        assertBoundCallsign(userId, nextCallsign);
        normalized.put("callsign", nextCallsign);
        return update("address", id, normalized, operator);
    }

    public boolean deleteAddressByBoundUser(String userId, Long id, String operator) {
        var existing = get("address", id);
        if (existing == null) {
            return false;
        }
        var existingCallsign = Objects.toString(existing.getOrDefault("callsign", ""), "").trim().toUpperCase();
        assertBoundCallsign(userId, existingCallsign);
        return softDelete("address", id, operator);
    }

    public List<Map<String, Object>> queryMyQsoByBoundUser(String userId, String callsign) {
        var approved = approvedCallsignsOfUser(userId);
        if (approved.isEmpty()) {
            return List.of();
        }
        var target = Objects.toString(callsign, "").trim().toUpperCase();
        return list("qso").stream()
            .filter(q -> {
                var c = Objects.toString(q.getOrDefault("peerCallsign", ""), "").trim().toUpperCase();
                return approved.contains(c) && (target.isBlank() || target.equals(c));
            })
            .toList();
    }

    public List<Map<String, Object>> queryMyCardsByBoundUser(String userId, String callsign) {
        var approved = approvedCallsignsOfUser(userId);
        if (approved.isEmpty()) {
            return List.of();
        }
        var target = Objects.toString(callsign, "").trim().toUpperCase();
        return list("card").stream()
            .filter(c -> {
                var v = Objects.toString(c.getOrDefault("peerCallsign", ""), "").trim().toUpperCase();
                return approved.contains(v) && (target.isBlank() || target.equals(v));
            })
            .toList();
    }

    private void assertBoundCallsign(String userId, String callsign) {
        var userKey = Objects.toString(userId, "").trim();
        var target = Objects.toString(callsign, "").trim().toUpperCase();
        if (userKey.isBlank() || target.isBlank()) {
            throw new IllegalArgumentException("callsign is required");
        }
        if (!approvedCallsignsOfUser(userKey).contains(target)) {
            throw new IllegalArgumentException("当前用户未绑定或未通过审核该呼号");
        }
    }

    private java.util.Set<String> approvedCallsignsOfUser(String userId) {
        var key = Objects.toString(userId, "").trim();
        if (key.isBlank()) {
            return java.util.Set.of();
        }
        return list("binding").stream()
            .filter(b -> key.equals(Objects.toString(b.getOrDefault("userId", ""), "").trim()))
            .filter(b -> "APPROVED".equalsIgnoreCase(Objects.toString(b.getOrDefault("status", ""), "").trim()))
            .map(b -> Objects.toString(b.getOrDefault("callsign", ""), "").trim().toUpperCase())
            .filter(v -> !v.isBlank())
            .collect(Collectors.toSet());
    }

    private boolean hasApprovedBindingForUser(String userName) {
        var key = Objects.toString(userName, "").trim();
        if (key.isBlank()) {
            return false;
        }
        return list("binding").stream()
            .filter(b -> key.equals(Objects.toString(b.getOrDefault("userId", ""), "").trim()))
            .anyMatch(b -> "APPROVED".equalsIgnoreCase(Objects.toString(b.getOrDefault("status", ""), "").trim()));
    }

    private void syncGrantHamRole(String userName, Map<String, String> authHeaders) {
        var roleName = hamRoleName();
        if (roleName.isBlank()) {
            throw new IllegalArgumentException("请先在本站配置中设置审核通过后赋权角色");
        }
        ensureRoleExists(roleName, authHeaders);
        var currentBindingName = findRoleBindingName(roleName, userName, authHeaders);
        if (currentBindingName != null) {
            return;
        }
        var bindingName = generateHamRoleBindingName(userName);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("apiVersion", "v1alpha1");
        payload.put("kind", "RoleBinding");
        payload.put("metadata", Map.of("name", bindingName));
        payload.put("roleRef", Map.of("apiGroup", "", "kind", "Role", "name", roleName));
        payload.put("subjects", List.of(Map.of("apiGroup", "", "kind", "User", "name", userName)));
        try {
            requestHaloApi("POST", "/api/v1alpha1/rolebindings", objectMapper.writeValueAsString(payload), authHeaders);
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("already exists")) {
                return;
            }
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("grant ham role failed: " + ex.getMessage(), ex);
        }
    }

    private void syncRevokeHamRole(String userName, Map<String, String> authHeaders) {
        var roleName = hamRoleName();
        if (roleName.isBlank()) {
            return;
        }
        var bindingName = findRoleBindingName(roleName, userName, authHeaders);
        if (bindingName == null) {
            return;
        }
        requestHaloApi("DELETE", "/api/v1alpha1/rolebindings/" + urlEncode(bindingName), null, authHeaders);
    }

    private String findRoleBindingName(String roleName, String userName, Map<String, String> authHeaders) {
        var page = 1;
        while (true) {
            var path = "/api/v1alpha1/rolebindings?page=" + page + "&size=500";
            var root = requestHaloApi("GET", path, null, authHeaders);
            var items = root.path("items");
            if (items.isArray()) {
                for (var item : items) {
                    var ref = item.path("roleRef");
                    var refName = ref.path("name").asText("");
                    if (!roleName.equalsIgnoreCase(refName)) {
                        continue;
                    }
                    var subjects = item.path("subjects");
                    if (!subjects.isArray()) {
                        continue;
                    }
                    for (var subject : subjects) {
                        var kind = subject.path("kind").asText("");
                        var name = subject.path("name").asText("");
                        if ("User".equalsIgnoreCase(kind) && userName.equalsIgnoreCase(name)) {
                            return item.path("metadata").path("name").asText("");
                        }
                    }
                }
            }
            if (root.path("hasNext").asBoolean(false)) {
                page++;
                continue;
            }
            return null;
        }
    }

    private void ensureRoleExists(String roleName, Map<String, String> authHeaders) {
        if (Objects.toString(roleName, "").trim().isBlank()) {
            throw new IllegalArgumentException("审核角色未配置");
        }
        try {
            requestHaloApi("GET", "/api/v1alpha1/roles/" + urlEncode(roleName), null, authHeaders);
        } catch (IllegalStateException ex) {
            throw new IllegalArgumentException("审核角色不存在: " + roleName);
        }
    }

    private String hamRoleName() {
        var roleName = Objects.toString(systemConfig.getOrDefault("hamRoleName", ""), "").trim();
        return roleName.isBlank() ? "ham" : roleName;
    }

    private String generateHamRoleBindingName(String userName) {
        var normalized = Objects.toString(userName, "").trim().toLowerCase()
            .replaceAll("[^a-z0-9-]", "-")
            .replaceAll("-{2,}", "-");
        if (normalized.isBlank()) {
            normalized = "user";
        }
        return "qsl-ham-" + normalized;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(Objects.toString(value, ""), StandardCharsets.UTF_8);
    }

    private JsonNode requestHaloApi(String method, String path, String body, Map<String, String> authHeaders) {
        try {
            var target = URI.create("http://127.0.0.1:8090" + path);
            var builder = HttpRequest.newBuilder(target);
            if ("POST".equalsIgnoreCase(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body));
                builder.header("Content-Type", "application/json");
            } else if ("DELETE".equalsIgnoreCase(method)) {
                builder.DELETE();
            } else {
                builder.GET();
            }
            if (authHeaders != null) {
                for (var entry : authHeaders.entrySet()) {
                    var key = entry.getKey();
                    var value = entry.getValue();
                    if (key == null || key.isBlank() || value == null || value.isBlank()) {
                        continue;
                    }
                    builder.header(key, value);
                }
            }
            var response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            var status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new IllegalStateException("halo api " + method + " " + path + " failed: "
                    + status + " " + response.body());
            }
            var text = Objects.toString(response.body(), "").trim();
            if (text.isBlank()) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            if (ex instanceof IllegalStateException state) {
                throw state;
            }
            throw new IllegalStateException("halo api request failed: " + method + " " + path + ", " + ex.getMessage(),
                ex);
        }
    }

    private boolean isAdminUser(String operator, Map<String, String> authHeaders) {
        try {
            var root = requestHaloApi("GET", "/apis/api.console.halo.run/v1alpha1/users/-", null, authHeaders);
            var roles = root.path("roles");
            if (roles.isArray()) {
                for (var role : roles) {
                    var roleName = role.path("metadata").path("name").asText("");
                    if ("super-role".equalsIgnoreCase(roleName)) {
                        return true;
                    }
                    var raw = role.path("metadata").path("annotations")
                        .path("rbac.authorization.halo.run/ui-permissions").asText("");
                    if (!raw.isBlank()) {
                        try {
                            var values = objectMapper.readTree(raw);
                            if (values.isArray()) {
                                for (var node : values) {
                                    if ("*".equals(node.asText(""))) {
                                        return true;
                                    }
                                }
                            }
                        } catch (Exception ignored) {
                            // ignore invalid role annotation payload
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // fallback to operator name check below
        }
        return "admin".equalsIgnoreCase(Objects.toString(operator, "").trim());
    }

    public List<Map<String, Object>> queryCardsByCallsign(String callsign) {
        var keyword = Objects.toString(callsign, "").trim().toLowerCase();
        return list("card").stream()
            .filter(c -> keyword.isBlank() || containsCallsign(c, keyword))
            .map(c -> {
                ensureCardStatusDefaults(c);
                var item = new LinkedHashMap<String, Object>();
                item.put("id", c.get("id"));
                item.put("peerCallsign", c.get("peerCallsign"));
                item.put("cardType", c.get("cardType"));
                item.put("productionStatus", c.get("productionStatus"));
                item.put("sentStatus", c.get("sentStatus"));
                item.put("sentAt", c.get("sentAt"));
                item.put("envelopePrinted", c.getOrDefault("envelopePrinted", false));
                item.put("envelopePrintedAt", c.get("envelopePrintedAt"));
                item.put("confirmStatus", c.get("confirmStatus"));
                item.put("confirmedAt", c.get("confirmedAt"));
                item.put("returnCardStatus", c.get("returnCardStatus"));
                item.put("returnedAt", c.get("returnedAt"));
                item.put("receivedStatus", c.get("receivedStatus"));
                item.put("receivedAt", c.get("receivedAt"));
                item.put("reissueCount", c.get("reissueCount"));
                item.put("cardDate", c.get("cardDate"));
                item.put("cardTime", c.get("cardTime"));
                return item;
            }).collect(Collectors.toList());
    }

    private boolean containsCallsign(Map<String, Object> card, String keyword) {
        for (String key : List.of("peerCallsign", "bindCallsign", "callsign")) {
            var value = Objects.toString(card.getOrDefault(key, ""), "").trim().toLowerCase();
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public List<Map<String, Object>> queryMyCards(String callsign) {
        return list("card").stream()
            .filter(c -> callsign == null || callsign.isBlank() || callsign.equals(c.get("peerCallsign")))
            .toList();
    }

    public List<Map<String, Object>> queryMyCards(String userId, String callsign) {
        return list("card").stream()
            .filter(c -> callsign == null || callsign.isBlank() || callsign.equals(c.get("peerCallsign")))
            .filter(c -> isUserBoundCallsign(userId, Objects.toString(c.getOrDefault("peerCallsign", ""))))
            .toList();
    }

    public List<Map<String, Object>> queryMyQso(String callsign) {
        return list("qso").stream()
            .filter(q -> callsign == null || callsign.isBlank() || callsign.equals(q.get("peerCallsign")))
            .toList();
    }

    public List<Map<String, Object>> queryMyQso(String userId, String callsign) {
        return list("qso").stream()
            .filter(q -> callsign == null || callsign.isBlank() || callsign.equals(q.get("peerCallsign")))
            .filter(q -> isUserBoundCallsign(userId, Objects.toString(q.getOrDefault("peerCallsign", ""))))
            .toList();
    }

    public Map<String, Object> importBackupData(Map<String, Object> payload, String operator) {
        return executeWithoutMailNotification(() -> {
            // Import policy: new value overrides old value; null does not override non-null.
            var qsoRows = filterQsoRowsByCardType(
                castMapList(payload.get("qsoRecords")),
                castMapList(payload.get("qslCardRecords")));
            var qsoImported = importByDedupe("qso", qsoRows,
                List.of("peerCallsign", "qsoDate", "qsoTime", "frequency", "mode"), operator);
            var cardsToImport = castMapList(payload.get("qslCardRecords"));
            resolveImportedCardQsoLinks(cardsToImport);
            var cardImported = importByDedupe("card", cardsToImport,
                List.of("cardType", "peerCallsign", "cardDate", "cardTime", "qsoRecordId"), operator);
            var addressImported = importByDedupe("address", castMapList(payload.get("addressBooks")),
                List.of("callsign", "address"), operator);

            var task = create("task", Map.of(
                "taskType", "IMPORT",
                "status", "COMPLETED",
                "fileName", "payload-import",
                "summary", "qso=" + qsoImported + ", card=" + cardImported + ", address=" + addressImported), operator);
            return Map.of("task", task, "qsoImported", qsoImported, "cardImported", cardImported,
                "addressImported", addressImported);
        });
    }

    private List<Map<String, Object>> filterQsoRowsByCardType(List<Map<String, Object>> qsoRows,
        List<Map<String, Object>> cardRows) {
        if (qsoRows == null || qsoRows.isEmpty()) {
            return qsoRows == null ? List.of() : qsoRows;
        }
        if (cardRows == null || cardRows.isEmpty()) {
            return qsoRows;
        }

        var requiredKeys = new HashSet<String>();
        for (var card : cardRows) {
            var cardType = normalize(Objects.toString(card.getOrDefault("cardType", "QSO"), ""));
            if (!"qso".equals(cardType) && !"listen".equals(cardType)) {
                continue;
            }
            var callsign = normalize(Objects.toString(card.getOrDefault("peerCallsign", ""), ""));
            var date = normalize(Objects.toString(card.getOrDefault("cardDate", ""), ""));
            var time = normalize(Objects.toString(card.getOrDefault("cardTime", ""), ""));
            if (callsign.isBlank() || date.isBlank() || time.isBlank()) {
                continue;
            }
            requiredKeys.add(callsign + "|" + date + "|" + time);
        }

        if (requiredKeys.isEmpty()) {
            return List.of();
        }

        return qsoRows.stream()
            .filter(Objects::nonNull)
            .filter(qso -> {
                var callsign = normalize(Objects.toString(qso.getOrDefault("peerCallsign", ""), ""));
                var date = normalize(Objects.toString(qso.getOrDefault("qsoDate", ""), ""));
                var time = normalize(Objects.toString(qso.getOrDefault("qsoTime", ""), ""));
                return requiredKeys.contains(callsign + "|" + date + "|" + time);
            })
            .toList();
    }

    private void resolveImportedCardQsoLinks(List<Map<String, Object>> cards) {
        if (cards == null || cards.isEmpty()) {
            return;
        }
        var qsoList = list("qso");
        if (qsoList.isEmpty()) {
            return;
        }

        var exact = new LinkedHashMap<String, Long>();
        var byCallsign = new LinkedHashMap<String, List<Long>>();
        for (var qso : qsoList) {
            var id = asLong(qso.get("id"));
            if (id == null) {
                continue;
            }
            var callsign = normalize(Objects.toString(qso.getOrDefault("peerCallsign", ""), ""));
            var date = normalize(Objects.toString(qso.getOrDefault("qsoDate", ""), ""));
            var time = normalize(Objects.toString(qso.getOrDefault("qsoTime", ""), ""));
            exact.putIfAbsent(callsign + "|" + date + "|" + time, id);
            byCallsign.computeIfAbsent(callsign, k -> new ArrayList<>()).add(id);
        }

        for (var card : cards) {
            var type = normalize(Objects.toString(card.getOrDefault("cardType", "QSO"), ""));
            if (!"qso".equals(type) && !"listen".equals(type)) {
                continue;
            }
            if (asLong(card.get("qsoRecordId")) != null) {
                continue;
            }
            var callsign = normalize(Objects.toString(card.getOrDefault("peerCallsign", ""), ""));
            var date = normalize(Objects.toString(card.getOrDefault("cardDate", ""), ""));
            var time = normalize(Objects.toString(card.getOrDefault("cardTime", ""), ""));
            var exactId = exact.get(callsign + "|" + date + "|" + time);
            if (exactId != null) {
                card.put("qsoRecordId", exactId);
                continue;
            }
            var matches = byCallsign.getOrDefault(callsign, List.of());
            if (matches.size() == 1) {
                card.put("qsoRecordId", matches.get(0));
            }
        }
    }

    public Map<String, Object> reportSummary() {
        var cards = list("card");
        var sent = cards.stream().filter(c -> "SENT".equals(c.get("sentStatus"))).count();
        var confirmed = cards.stream().filter(c -> "CONFIRMED".equals(c.get("confirmStatus"))).count();
        var pendingPrint = cards.stream().filter(c -> "PENDING_PRINT".equals(c.get("productionStatus"))).count();
        var notSent = cards.stream().filter(c -> "NOT_SENT".equals(c.get("sentStatus"))).count();
        var notConfirmed = cards.stream().filter(c -> "UNCONFIRMED".equals(c.get("confirmStatus"))).count();
        var returned = cards.stream().filter(c -> "RECEIVED".equals(c.get("returnCardStatus"))).count();
        var notReturned = cards.stream().filter(c -> "NOT_RECEIVED".equals(c.get("returnCardStatus"))).count();

        var data = new LinkedHashMap<String, Object>();
        data.put("sentCount", sent);
        data.put("confirmedCount", confirmed);
        data.put("returnedCount", returned);
        // Backward compatibility key, kept for existing front-end references.
        data.put("receivedCount", confirmed);
        data.put("pendingPrintCount", pendingPrint);
        data.put("pendingSendCount", notSent);
        data.put("pendingConfirmCount", notConfirmed);
        data.put("pendingReceiveCount", notReturned);
        data.put("total", cards.size());
        return data;
    }

    public List<Map<String, Object>> reportMonthlyTrend() {
        return list("card").stream()
            .collect(Collectors.groupingBy(
                c -> {
                    var d = Objects.toString(c.getOrDefault("cardDate", nowDateString()));
                    return YearMonth.parse(d.substring(0, 7));
                }, Collectors.counting()))
            .entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("month", e.getKey().toString());
                item.put("count", e.getValue());
                return item;
            })
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> reportCardTypeDistribution() {
        return list("card").stream()
            .collect(Collectors.groupingBy(c -> Objects.toString(c.getOrDefault("cardType", "UNKNOWN")),
                Collectors.counting()))
            .entrySet().stream()
            .map(e -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("cardType", e.getKey());
                item.put("count", e.getValue());
                return item;
            })
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> filterAudit(Map<String, String> query) {
        return auditLogs.values().stream()
            .filter(a -> matches(a, "objectType", query.get("objectType")))
            .filter(a -> matches(a, "objectId", query.get("objectId")))
            .filter(a -> matches(a, "relatedCallsign", query.get("relatedCallsign")))
            .filter(a -> matches(a, "operatorId", query.get("operatorId")))
            .filter(a -> matches(a, "operationType", query.get("operationType")))
            .filter(a -> matches(a, "result", query.get("result")))
            .sorted(Comparator.comparing(a -> Objects.toString(a.get("createdAt")), Comparator.reverseOrder()))
            .map(LinkedHashMap::new)
            .collect(Collectors.toList());
    }

    public List<Map<String, Object>> dashboardOverview(Map<String, String> filters) {
        var callsign = Objects.toString(filters.getOrDefault("callsign", "")).trim();
        var cardType = Objects.toString(filters.getOrDefault("cardType", "")).trim();
        var productionStatus = Objects.toString(filters.getOrDefault("productionStatus", "")).trim();
        var sentStatus = Objects.toString(filters.getOrDefault("sentStatus", "")).trim();
        var confirmStatus = Objects.toString(filters.getOrDefault("confirmStatus", "")).trim();
        var returnCardStatus = Objects.toString(filters.getOrDefault("returnCardStatus", "")).trim();
        // Compatible old filter key maps to return-card dimension.
        var receivedStatus = Objects.toString(filters.getOrDefault("receivedStatus", "")).trim();
        return list("card").stream()
            .peek(this::ensureCardStatusDefaults)
            .filter(c -> callsign.isBlank() || Objects.toString(c.getOrDefault("peerCallsign", "")).contains(callsign))
            .filter(c -> cardType.isBlank() || cardType.equalsIgnoreCase(Objects.toString(c.getOrDefault("cardType", ""))))
            .filter(c -> productionStatus.isBlank()
                || productionStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("productionStatus", ""))))
            .filter(c -> sentStatus.isBlank() || sentStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("sentStatus", ""))))
            .filter(c -> confirmStatus.isBlank()
                || confirmStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("confirmStatus", ""))))
            .filter(c -> returnCardStatus.isBlank()
                || returnCardStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("returnCardStatus", ""))))
            .filter(c -> receivedStatus.isBlank()
                || receivedStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("returnCardStatus", ""))))
            .collect(Collectors.toList());
    }

    public byte[] exportDashboardCsv(Map<String, String> filters) {
        var rows = dashboardOverview(filters);
        var builder = new StringBuilder("呼号,卡片类型,制作状态,寄出状态,确认收卡状态,回卡状态,发信时间,确认时间,回卡时间,补卡次数\n");
        for (var r : rows) {
            ensureCardStatusDefaults(r);
            builder.append(csv(r.get("peerCallsign"))).append(',')
                .append(csv(r.get("cardType"))).append(',')
                .append(csv(r.get("productionStatus"))).append(',')
                .append(csv(r.get("sentStatus"))).append(',')
                .append(csv(r.get("confirmStatus"))).append(',')
                .append(csv(r.get("returnCardStatus"))).append(',')
                .append(csv(r.get("sentAt"))).append(',')
                .append(csv(r.get("confirmedAt"))).append(',')
                .append(csv(r.get("returnedAt"))).append(',')
                .append(csv(r.get("reissueCount"))).append('\n');
        }
        return withBom(builder.toString());
    }

    private boolean matches(Map<String, Object> map, String key, String expected) {
        if (expected == null || expected.isBlank()) {
            return true;
        }
        return Objects.toString(map.getOrDefault(key, "")).contains(expected);
    }

    private Map<Long, Map<String, Object>> getStore(String type) {
        return switch (type) {
            case "bureau" -> bureaus;
            case "equipment" -> equipments;
            case "antenna" -> antennas;
            case "power" -> powers;
            case "mode" -> modes;
            case "qso" -> qsoRecords;
            case "card" -> cardRecords;
            case "request" -> exchangeRequests;
            case "binding" -> callsignBindings;
            case "address" -> addressBooks;
            case "task" -> importExportTasks;
            case "audit" -> auditLogs;
            default -> throw new IllegalArgumentException("Unsupported store type: " + type);
        };
    }

    private boolean isUserBoundCallsign(String userId, String callsign) {
        if (userId == null || userId.isBlank() || callsign == null || callsign.isBlank()) {
            return false;
        }
        return callsignBindings.values().stream()
            .filter(b -> !isDeleted(b))
            .anyMatch(b -> userId.equals(Objects.toString(b.getOrDefault("userId", "")))
                && "APPROVED".equalsIgnoreCase(Objects.toString(b.getOrDefault("status", "")))
                && callsign.equalsIgnoreCase(Objects.toString(b.getOrDefault("callsign", ""))));
    }

    private int importByDedupe(String type, List<Map<String, Object>> records, List<String> dedupeKeys, String operator) {
        int count = 0;
        for (var record : records) {
            var existing = findByDedupe(type, record, dedupeKeys);
            if (existing == null) {
                create(type, record, operator);
                count++;
            } else {
                var updateData = new LinkedHashMap<String, Object>();
                for (var entry : record.entrySet()) {
                    var k = entry.getKey();
                    var v = entry.getValue();
                    if (v != null) {
                        updateData.put(k, v);
                    }
                }
                update(type, Long.parseLong(String.valueOf(existing.get("id"))), updateData, operator);
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> findByDedupe(String type, Map<String, Object> target, List<String> dedupeKeys) {
        return list(type).stream()
            .filter(item -> dedupeKeys.stream().allMatch(k ->
                Objects.equals(
                    normalize(item.get(k)),
                    normalize(target.get(k)))))
            .findFirst()
            .orElse(null);
    }

    private String normalize(Object value) {
        return value == null ? "" : Objects.toString(value, "").trim().toLowerCase();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castMapList(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
            .filter(i -> i instanceof Map<?, ?>)
            .map(i -> (Map<String, Object>) i)
            .collect(Collectors.toList());
    }

    private void validateAddressBookUnique(Long currentId, Map<String, Object> payload) {
        validateAddressBookUnique(currentId, payload, null);
    }

    private void validateBindingPayloadForCreate(Map<String, Object> payload) {
        var callsign = Objects.toString(payload.getOrDefault("callsign", ""), "").trim();
        if (callsign.isBlank()) {
            throw new IllegalArgumentException("callsign is required");
        }
        validateBindingProof(payload, null);
    }

    private void validateBindingPayloadForUpdate(Long currentId, Map<String, Object> payload, Map<String, Object> existing) {
        var callsign = payload.containsKey("callsign")
            ? Objects.toString(payload.getOrDefault("callsign", ""), "").trim()
            : Objects.toString(existing.getOrDefault("callsign", ""), "").trim();
        if (callsign.isBlank()) {
            throw new IllegalArgumentException("callsign is required");
        }
        if (payload.containsKey("verifyMethod")
            || payload.containsKey("radioLicenseImage")
            || payload.containsKey("hamcqProofImage")
            || payload.containsKey("legacyCardId")
            || payload.containsKey("legacyPhone")) {
            validateBindingProof(payload, existing);
            payload.put("verifyMethod", resolveVerifyMethod(payload, existing));
        }
        for (var entry : callsignBindings.entrySet()) {
            if (currentId != null && currentId.equals(entry.getKey())) {
                continue;
            }
            var item = entry.getValue();
            if (isDeleted(item)) {
                continue;
            }
            var c = Objects.toString(item.getOrDefault("callsign", ""), "").trim();
            var u = Objects.toString(item.getOrDefault("userId", ""), "").trim();
            if (u.equalsIgnoreCase(Objects.toString(existing.getOrDefault("userId", ""), "").trim())
                && c.equalsIgnoreCase(callsign)
                && !"REJECTED".equalsIgnoreCase(Objects.toString(item.getOrDefault("status", ""), "").trim())
                && !"UNBOUND".equalsIgnoreCase(Objects.toString(item.getOrDefault("status", ""), "").trim())) {
                throw new IllegalArgumentException("该呼号已有未完成或已通过的绑定申请");
            }
        }
    }

    private void validateBindingProof(Map<String, Object> payload, Map<String, Object> existing) {
        var license = mergeString(payload, existing, "radioLicenseImage");
        var hamcq = mergeString(payload, existing, "hamcqProofImage");
        var legacyCardId = mergeString(payload, existing, "legacyCardId");
        var legacyPhone = mergeString(payload, existing, "legacyPhone");
        var hasLicense = !license.isBlank();
        var hasHamcq = !hamcq.isBlank();
        var hasLegacy = !legacyCardId.isBlank() && !legacyPhone.isBlank();
        if (!hasLicense && !hasHamcq && !hasLegacy) {
            throw new IllegalArgumentException("证明材料三选一，至少填写一种");
        }
    }

    private String resolveVerifyMethod(Map<String, Object> payload, Map<String, Object> existing) {
        var license = mergeString(payload, existing, "radioLicenseImage");
        var hamcq = mergeString(payload, existing, "hamcqProofImage");
        var legacyCardId = mergeString(payload, existing, "legacyCardId");
        var legacyPhone = mergeString(payload, existing, "legacyPhone");
        if (!license.isBlank()) {
            return "LICENSE_IMAGE";
        }
        if (!hamcq.isBlank()) {
            return "HAMCQ_IMAGE";
        }
        if (!legacyCardId.isBlank() && !legacyPhone.isBlank()) {
            return "LEGACY_CARD_PHONE";
        }
        return "";
    }

    private String mergeString(Map<String, Object> payload, Map<String, Object> existing, String key) {
        if (payload != null && payload.containsKey(key)) {
            return Objects.toString(payload.getOrDefault(key, ""), "").trim();
        }
        if (existing != null) {
            return Objects.toString(existing.getOrDefault(key, ""), "").trim();
        }
        return "";
    }

    private void validateAddressBookUnique(Long currentId, Map<String, Object> payload, Map<String, Object> existing) {
        var callsign = Objects.toString(payload.getOrDefault("callsign",
            existing == null ? "" : existing.getOrDefault("callsign", ""))).trim();
        var address = Objects.toString(payload.getOrDefault("address",
            existing == null ? "" : existing.getOrDefault("address", ""))).trim();
        for (var entry : addressBooks.entrySet()) {
            if (currentId != null && currentId.equals(entry.getKey())) {
                continue;
            }
            var item = entry.getValue();
            if (isDeleted(item)) {
                continue;
            }
            var c = Objects.toString(item.getOrDefault("callsign", "")).trim();
            var a = Objects.toString(item.getOrDefault("address", "")).trim();
            if (c.equalsIgnoreCase(callsign) && a.equalsIgnoreCase(address)) {
                throw new IllegalArgumentException("Address book duplicate: callsign + address");
            }
        }
    }

    private void normalizeAddressAssociation(String type, Map<String, Object> payload,
        Map<String, Object> existing, String operator) {
        if (!"card".equals(type) && !"request".equals(type)) {
            return;
        }
        var addressId = asLong(payload.get("addressId"));
        if (addressId != null) {
            payload.put("addressId", addressId);
            removeInlineAddressFields(payload);
            return;
        }
        if (!containsInlineAddressFields(payload)) {
            return;
        }
        var callsign = resolveAddressCallsign(type, payload, existing);
        var resolvedAddressId = upsertAddressAndGetId(callsign, payload, existing, operator);
        if (resolvedAddressId != null) {
            payload.put("addressId", resolvedAddressId);
        }
        removeInlineAddressFields(payload);
    }

    private String resolveAddressCallsign(String type, Map<String, Object> payload, Map<String, Object> existing) {
        var current = "request".equals(type)
            ? Objects.toString(payload.getOrDefault("bindCallsign", ""), "").trim()
            : Objects.toString(payload.getOrDefault("peerCallsign", ""), "").trim();
        if (!current.isBlank()) {
            return current;
        }
        if (existing == null) {
            return "";
        }
        return "request".equals(type)
            ? Objects.toString(existing.getOrDefault("bindCallsign", ""), "").trim()
            : Objects.toString(existing.getOrDefault("peerCallsign", ""), "").trim();
    }

    private boolean containsInlineAddressFields(Map<String, Object> payload) {
        for (var key : ADDRESS_RELATED_KEYS) {
            if (payload.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private void removeInlineAddressFields(Map<String, Object> payload) {
        for (var key : ADDRESS_RELATED_KEYS) {
            payload.remove(key);
        }
    }

    private Long upsertAddressAndGetId(String callsign, Map<String, Object> payload, Map<String, Object> existing,
        String operator) {
        var existingAddressId = existing == null ? null : asLong(existing.get("addressId"));
        var existingAddress = existingAddressId == null ? null : addressBooks.get(existingAddressId);

        var resolvedAddress = firstNonBlank(
            Objects.toString(payload.getOrDefault("address", ""), "").trim(),
            existingAddress == null ? "" : Objects.toString(existingAddress.getOrDefault("address", ""), "").trim(),
            existing == null ? "" : Objects.toString(existing.getOrDefault("address", ""), "").trim()
        );
        if (callsign.isBlank() || resolvedAddress.isBlank()) {
            return existingAddressId;
        }

        var target = findAddressByCallsignAndAddress(callsign, resolvedAddress);
        Long targetId;
        if (target != null) {
            targetId = asLong(target.get("id"));
        } else {
            var created = create("address", Map.of(
                "callsign", callsign,
                "address", resolvedAddress
            ), operator);
            targetId = asLong(created.get("id"));
        }

        if (targetId == null) {
            return null;
        }

        var updateData = new LinkedHashMap<String, Object>();
        updateData.put("callsign", callsign);
        updateData.put("address", resolvedAddress);
        for (var key : List.of("name", "phone", "postcode", "email")) {
            var value = Objects.toString(payload.getOrDefault(key, ""), "").trim();
            if (!value.isBlank()) {
                updateData.put(key, value);
            }
        }
        update("address", targetId, updateData, operator);
        return targetId;
    }

    private Map<String, Object> findAddressByCallsignAndAddress(String callsign, String address) {
        for (var item : addressBooks.values()) {
            if (item == null || isDeleted(item)) {
                continue;
            }
            var c = Objects.toString(item.getOrDefault("callsign", ""), "").trim();
            var a = Objects.toString(item.getOrDefault("address", ""), "").trim();
            if (c.equalsIgnoreCase(callsign) && a.equalsIgnoreCase(address)) {
                return item;
            }
        }
        return null;
    }

    private void hydrateAddressFields(Map<String, Object> target) {
        if (target == null) {
            return;
        }
        var addressId = asLong(target.get("addressId"));
        if (addressId == null) {
            return;
        }
        var address = addressBooks.get(addressId);
        if (address == null || isDeleted(address)) {
            return;
        }
        for (var key : ADDRESS_RELATED_KEYS) {
            var current = Objects.toString(target.getOrDefault(key, ""), "").trim();
            if (!current.isBlank()) {
                continue;
            }
            var value = Objects.toString(address.getOrDefault(key, ""), "").trim();
            if (!value.isBlank()) {
                target.put(key, value);
            }
        }
    }

    private void validateCardPayloadForCreate(Map<String, Object> payload) {
        var type = normalizeCardType(payload.get("cardType"));
        if (List.of("QSO", "LISTEN").contains(type)) {
            var qsoId = payload.get("qsoRecordId");
            if (qsoId == null) {
                throw new IllegalArgumentException("qsoRecordId is required for QSO/LISTEN card");
            }
            validateQsoAssociationUnique(Long.parseLong(String.valueOf(qsoId)), null);
        }
    }

    private void validateCardPayloadForUpdate(Long id, Map<String, Object> payload, Map<String, Object> existing) {
        var type = normalizeCardType(payload.getOrDefault("cardType", existing.get("cardType")));
        if (List.of("QSO", "LISTEN").contains(type)) {
            var qsoIdObj = payload.containsKey("qsoRecordId") ? payload.get("qsoRecordId") : existing.get("qsoRecordId");
            if (qsoIdObj == null) {
                throw new IllegalArgumentException("qsoRecordId is required for QSO/LISTEN card");
            }
            validateQsoAssociationUnique(Long.parseLong(String.valueOf(qsoIdObj)), id);
        }
    }

    private String normalizeCardType(Object cardType) {
        return Objects.toString(cardType, "").trim().toUpperCase();
    }

    private void validateQsoAssociationUnique(Long qsoRecordId, Long currentCardId) {
        if (!qsoRecords.containsKey(qsoRecordId) || isDeleted(qsoRecords.get(qsoRecordId))) {
            throw new IllegalArgumentException("qsoRecordId does not exist");
        }
        for (var entry : cardRecords.entrySet()) {
            if (currentCardId != null && currentCardId.equals(entry.getKey())) {
                continue;
            }
            var card = entry.getValue();
            if (isDeleted(card)) {
                continue;
            }
            if (qsoRecordId.equals(asLong(card.get("qsoRecordId")))) {
                throw new IllegalArgumentException("qsoRecordId already bound to another card");
            }
        }
    }

    private void overrideCardDateTimeByQso(Map<String, Object> payload, Map<String, Object> existing) {
        var qsoIdObj = payload.containsKey("qsoRecordId")
            ? payload.get("qsoRecordId")
            : (existing == null ? null : existing.get("qsoRecordId"));
        if (qsoIdObj == null) {
            return;
        }
        var qsoId = asLong(qsoIdObj);
        if (qsoId == null) {
            return;
        }
        var qso = qsoRecords.get(qsoId);
        if (qso == null || isDeleted(qso)) {
            return;
        }
        var qsoDate = Objects.toString(qso.getOrDefault("qsoDate", ""), "").trim();
        var qsoTime = Objects.toString(qso.getOrDefault("qsoTime", ""), "").trim();
        var timezone = Objects.toString(qso.getOrDefault("timezone", ""), "").trim();
        if (!qsoDate.isBlank()) {
            payload.put("cardDate", qsoDate);
        }
        if (!qsoTime.isBlank()) {
            payload.put("cardTime", qsoTime);
        }
        if (!timezone.isBlank()) {
            payload.put("timezone", timezone);
        }
    }

    private void normalizeCardPrintCompat(String type, Map<String, Object> payload) {
        if (!"card".equals(type) || payload == null) {
            return;
        }
        if (payload.containsKey("cardPrinted")) {
            var cardPrinted = Boolean.parseBoolean(Objects.toString(payload.get("cardPrinted"), "false"));
            if (cardPrinted) {
                payload.put("productionStatus", "PRINTED");
                if (!payload.containsKey("printedAt")) {
                    var legacyPrintedAt = Objects.toString(payload.getOrDefault("cardPrintedAt", ""), "").trim();
                    payload.put("printedAt", legacyPrintedAt.isBlank() ? nowString() : legacyPrintedAt);
                }
            }
            payload.remove("cardPrinted");
        }
        if (payload.containsKey("cardPrintedAt")) {
            if (!payload.containsKey("printedAt")) {
                payload.put("printedAt", payload.get("cardPrintedAt"));
            }
            payload.remove("cardPrintedAt");
        }
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean isDeleted(Map<String, Object> item) {
        return Boolean.TRUE.equals(item.get("deleted"));
    }

    private void ensureCardStatusDefaults(Map<String, Object> card) {
        if (card == null) {
            return;
        }
        card.putIfAbsent("envelopePrinted", false);
        var legacyReceived = Objects.toString(card.getOrDefault("receivedStatus", "NOT_RECEIVED"));
        if (!card.containsKey("confirmStatus")) {
            card.put("confirmStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "CONFIRMED" : "UNCONFIRMED");
        }
        if (!card.containsKey("returnCardStatus")) {
            card.put("returnCardStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "RECEIVED" : "NOT_RECEIVED");
        }
        var returnStatus = Objects.toString(card.getOrDefault("returnCardStatus", "NOT_RECEIVED"));
        card.put("receivedStatus", "RECEIVED".equalsIgnoreCase(returnStatus) ? "RECEIVED" : "NOT_RECEIVED");
    }

    private OffsetDateTime nowOffsetDateTime() {
        return OffsetDateTime.now().withNano(0);
    }

    private String nowString() {
        return nowOffsetDateTime().toString();
    }

    private String nowDateString() {
        return nowOffsetDateTime().toLocalDate().toString();
    }

    private String nowTimeString() {
        return nowOffsetDateTime().toLocalTime().toString();
    }

    private void writeAudit(String objectType, String objectId, String operation, String operator,
        String result, Map<String, Object> before, Map<String, Object> after) {
        var id = auditIdGenerator.incrementAndGet();
        var log = new LinkedHashMap<String, Object>();
        log.put("id", id);
        log.put("objectType", objectType);
        log.put("objectId", objectId);
        log.put("relatedCallsign", extractCallsign(after, before));
        log.put("operationType", operation);
        log.put("beforeSnapshot", before);
        log.put("afterSnapshot", after);
        log.put("result", result);
        log.put("operatorId", operator);
        log.put("operatorIp", "127.0.0.1");
        log.put("createdAt", nowString());
        auditLogs.put(id, log);
        persistState();
    }

    private long nextEntityId(String type) {
        var key = Objects.toString(type, "").trim().toLowerCase();
        if (key.isBlank()) {
            key = "default";
        }
        var generator = entityIdGenerators.computeIfAbsent(key, this::buildEntityGenerator);
        return generator.incrementAndGet();
    }

    private AtomicLong buildEntityGenerator(String type) {
        var store = getStore(type);
        var max = store.values().stream()
            .mapToLong(item -> {
                var raw = item.get("id");
                if (raw == null) {
                    return 1000L;
                }
                try {
                    return Long.parseLong(String.valueOf(raw));
                } catch (Exception ignored) {
                    return 1000L;
                }
            })
            .max()
            .orElse(1000L);
        return new AtomicLong(Math.max(1000L, max));
    }

    private String extractCallsign(Map<String, Object> after, Map<String, Object> before) {
        if (after != null) {
            for (String key : List.of("peerCallsign", "bindCallsign", "callsign")) {
                if (after.containsKey(key) && after.get(key) != null) {
                    return after.get(key).toString();
                }
            }
        }
        if (before != null) {
            for (String key : List.of("peerCallsign", "bindCallsign", "callsign")) {
                if (before.containsKey(key) && before.get(key) != null) {
                    return before.get(key).toString();
                }
            }
        }
        return null;
    }

    private List<Long> castIds(Object obj) {
        if (obj == null) {
            return List.of();
        }
        if (obj instanceof Collection<?> c) {
            return c.stream().map(v -> Long.parseLong(String.valueOf(v))).collect(Collectors.toList());
        }
        return List.of(Long.parseLong(String.valueOf(obj)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castMap(Object obj) {
        if (obj instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return null;
    }

    private Map<String, Object> parseImportPayload(String fileName, byte[] bytes, String dataset) throws Exception {
        var lower = fileName.toLowerCase();
        if (lower.endsWith(".json")) {
            return parseJsonPayload(new String(bytes, StandardCharsets.UTF_8), dataset);
        }
        if (lower.endsWith(".csv")) {
            return parseCsvPayload(new String(bytes, StandardCharsets.UTF_8), fileName, dataset);
        }
        throw new IllegalArgumentException("unsupported file type, only .json/.csv");
    }

    private Map<String, Object> parseJsonPayload(String text, String dataset) throws Exception {
        var sanitized = stripBom(text);
        var root = objectMapper.readTree(sanitized);
        if (root.isArray()) {
            var normalized = normalizeDataset(dataset);
            if (normalized == null) {
                throw new IllegalArgumentException("dataset is required when JSON root is array");
            }
            var list = objectMapper.convertValue(root, LIST_MAP_TYPE);
            return payloadForSingleDataset(normalized, list);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("json must be object or array");
        }
        var payload = objectMapper.convertValue(root, MAP_TYPE);
        if (payload.containsKey("qsoRecords") || payload.containsKey("qslCardRecords") || payload.containsKey("addressBooks")) {
            var normalized = normalizeDataset(dataset);
            if (normalized != null && !"all".equals(normalized)) {
                return payloadForSingleDataset(normalized, switch (normalized) {
                    case "qso" -> castMapList(payload.get("qsoRecords"));
                    case "card" -> castMapList(payload.get("qslCardRecords"));
                    case "address" -> castMapList(payload.get("addressBooks"));
                    default -> List.of();
                });
            }
            return payload;
        }
        var normalized = normalizeDataset(dataset);
        if (normalized == null) {
            throw new IllegalArgumentException("dataset is required for simple object json");
        }
        return payloadForSingleDataset(normalized, List.of(payload));
    }

    private Map<String, Object> parseCsvPayload(String text, String fileName, String dataset) {
        var rows = parseCsvRows(text);
        var normalized = normalizeDataset(dataset);
        if (normalized == null) {
            normalized = inferDatasetByName(fileName);
        }
        if (normalized == null) {
            throw new IllegalArgumentException("dataset is required for csv import");
        }
        if ("address".equals(normalized)) {
            var converted = rows.stream().map(this::mapAddressRow).toList();
            return payloadForSingleDataset("address", converted);
        }
        if ("qso".equals(normalized)) {
            var converted = rows.stream().map(this::mapQsoRow).toList();
            return payloadForSingleDataset("qso", converted);
        }
        var qsoRows = new ArrayList<Map<String, Object>>();
        var cardRows = new ArrayList<Map<String, Object>>();
        var addressRows = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            qsoRows.add(mapQsoRow(row));
            cardRows.add(mapCardRow(row));
            addressRows.add(mapAddressRow(row));
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("qsoRecords", qsoRows);
        payload.put("qslCardRecords", cardRows);
        payload.put("addressBooks", addressRows);
        return payload;
    }

    private Map<String, Object> payloadForSingleDataset(String dataset, List<Map<String, Object>> list) {
        var payload = new LinkedHashMap<String, Object>();
        switch (dataset) {
            case "qso" -> payload.put("qsoRecords", list);
            case "card" -> payload.put("qslCardRecords", list);
            case "address" -> payload.put("addressBooks", list);
            default -> throw new IllegalArgumentException("invalid dataset: " + dataset);
        }
        return payload;
    }

    private String inferDatasetByName(String fileName) {
        var lower = Objects.toString(fileName, "").toLowerCase();
        if (lower.contains("qso")) {
            return "qso";
        }
        if (lower.contains("card")) {
            return "card";
        }
        if (lower.contains("address")) {
            return "all";
        }
        return null;
    }

    private String normalizeDataset(String dataset) {
        var value = Objects.toString(dataset, "").trim().toLowerCase();
        if (value.isBlank()) {
            return null;
        }
        return switch (value) {
            case "qso", "card", "address", "all" -> value;
            default -> null;
        };
    }

    private List<Map<String, Object>> parseCsvRows(String csvText) {
        var text = stripBom(csvText);
        var records = parseCsvRecords(text);
        if (records.isEmpty()) {
            return List.of();
        }
        var headers = records.get(0).stream().map(h -> h == null ? "" : h.trim()).toList();
        var rows = new ArrayList<Map<String, Object>>();
        for (int i = 1; i < records.size(); i++) {
            var cols = records.get(i);
            if (cols.stream().allMatch(v -> Objects.toString(v, "").isBlank())) {
                continue;
            }
            var row = new LinkedHashMap<String, Object>();
            for (int c = 0; c < headers.size(); c++) {
                var key = headers.get(c);
                if (key.isBlank()) {
                    continue;
                }
                row.put(key, c < cols.size() ? cols.get(c) : "");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<List<String>> parseCsvRecords(String text) {
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                if (inQuotes && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (ch == ',' && !inQuotes) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if ((ch == '\n' || ch == '\r') && !inQuotes) {
                if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private String stripBom(String text) {
        if (text == null) {
            return "";
        }
        if (text.startsWith("\uFEFF")) {
            return text.substring(1);
        }
        return text;
    }

    private Map<String, Object> mapAddressRow(Map<String, Object> row) {
        var mapped = new LinkedHashMap<String, Object>();
        mapped.put("callsign", pick(row, "callsign", "peerCallsign", "对方呼号"));
        mapped.put("address", pick(row, "address", "收件地址", "邮寄地址"));
        mapped.put("name", pick(row, "name", "姓名"));
        mapped.put("phone", pick(row, "phone", "电话"));
        mapped.put("email", pick(row, "email", "邮箱", "电子邮箱"));
        mapped.put("postcode", pick(row, "zipcode", "postcode", "邮编"));
        return mapped;
    }

    private Map<String, Object> mapQsoRow(Map<String, Object> row) {
        var mapped = new LinkedHashMap<String, Object>();
        mapped.put("peerCallsign", pick(row, "peerCallsign", "callsign", "to_radio", "对方呼号"));
        mapped.put("qsoDate", pick(row, "qsoDate", "qso_date", "通联日期"));
        mapped.put("qsoTime", pick(row, "qsoTime", "qso_time", "通联时间"));
        mapped.put("frequency", pick(row, "frequency", "qso_freq_mhz", "频率"));
        mapped.put("mode", pick(row, "mode", "qso_mode", "模式"));
        return mapped;
    }

    private Map<String, Object> mapCardRow(Map<String, Object> row) {
        return mapCardRow(row, false);
    }

    private Map<String, Object> mapCardRow(Map<String, Object> row, boolean forceEyeball) {
        var mapped = new LinkedHashMap<String, Object>();
        var eyeball = "1".equals(normalize(pick(row, "qso_eyeball")));
        var swl = "1".equals(normalize(pick(row, "qso_swl")));
        mapped.put("peerCallsign", pick(row, "peerCallsign", "callsign", "to_radio", "对方呼号"));
        mapped.put("cardType", forceEyeball ? "EYEBALL" : (eyeball ? "EYEBALL" : (swl ? "LISTEN" : "QSO")));
        mapped.put("cardDate", pick(row, "cardDate", "card_date", "qso_date", "qsoDate"));
        mapped.put("cardTime", pick(row, "cardTime", "card_time", "qso_time", "qsoTime"));
        mapped.put("productionStatus", "1".equals(normalize(pick(row, "printed_card"))) ? "PRINTED" : "PENDING_PRINT");
        mapped.put("sentStatus", "1".equals(normalize(pick(row, "sent_card"))) ? "SENT" : "NOT_SENT");
        boolean received = "1".equals(normalize(pick(row, "received_card")));
        mapped.put("confirmStatus", received ? "CONFIRMED" : "UNCONFIRMED");
        mapped.put("returnCardStatus", received ? "RECEIVED" : "NOT_RECEIVED");
        mapped.put("reissueCount", toInt(pick(row, "reissueCount", "补卡次数"), 0));
        return mapped;
    }

    private String pick(Map<String, Object> row, String... keys) {
        for (var key : keys) {
            if (row.containsKey(key) && row.get(key) != null) {
                var value = Objects.toString(row.get(key), "").trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return "";
    }

    private int toInt(String value, int defaultValue) {
        try {
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return Integer.parseInt(value.trim());
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private String csv(Object value) {
        var raw = Objects.toString(value, "");
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private List<Map<String, Object>> snapshotStoreAllFields(Map<Long, Map<String, Object>> store) {
        return store.values().stream()
            .sorted(Comparator.comparing(item -> ((Number) item.get("id")).longValue()))
            .map(LinkedHashMap::new)
            .collect(Collectors.toList());
    }

    private Map<String, Object> getRelatedQso(Map<String, Object> card) {
        var qsoId = asLong(card.get("qsoRecordId"));
        if (qsoId == null) {
            return Map.of();
        }
        var qso = qsoRecords.get(qsoId);
        if (qso == null || isDeleted(qso)) {
            return Map.of();
        }
        return qso;
    }

    private String getNameById(Map<Long, Map<String, Object>> store, Long id) {
        if (id == null) {
            return "";
        }
        var item = store.get(id);
        if (item == null || isDeleted(item)) {
            return "";
        }
        return Objects.toString(item.getOrDefault("name", ""), "");
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private boolean isMailNotificationSuppressed() {
        return Boolean.TRUE.equals(suppressMailNotification.get());
    }

    private <T> T executeWithoutMailNotification(Supplier<T> supplier) {
        var previous = suppressMailNotification.get();
        suppressMailNotification.set(true);
        try {
            return supplier.get();
        } finally {
            suppressMailNotification.set(previous);
        }
    }

    private byte[] withBom(String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        var result = new byte[bom.length + bytes.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(bytes, 0, result, bom.length, bytes.length);
        return result;
    }

    @SuppressWarnings("unchecked")
    private void syncPeerLibraryFromLocalDictionary(String type, Map<String, Object> item) {
        if (item == null) {
            return;
        }
        var name = Objects.toString(item.getOrDefault("name", ""), "").trim();
        if (name.isBlank()) {
            return;
        }
        String systemConfigKey;
        switch (Objects.toString(type, "").trim().toLowerCase()) {
            case "equipment" -> systemConfigKey = "peerEquipmentLibrary";
            case "antenna" -> systemConfigKey = "peerAntennaLibrary";
            case "power" -> systemConfigKey = "peerPowerLibrary";
            default -> {
                return;
            }
        }
        var raw = systemConfig.get(systemConfigKey);
        List<String> values;
        if (raw instanceof List<?> list) {
            values = list.stream()
                .map(v -> Objects.toString(v, "").trim())
                .filter(v -> !v.isBlank())
                .collect(Collectors.toCollection(ArrayList::new));
        } else {
            values = new ArrayList<>();
        }
        var exists = values.stream().anyMatch(v -> v.equalsIgnoreCase(name));
        if (!exists) {
            values.add(name);
            systemConfig.put(systemConfigKey, values);
        }
    }
}
