package run.halo.qsl;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class QslDataService {

    private final AtomicLong idGenerator = new AtomicLong(1000);

    private final Map<Long, Map<String, Object>> bureaus = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> equipments = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> antennas = new ConcurrentHashMap<>();
    private final Map<Long, Map<String, Object>> powers = new ConcurrentHashMap<>();
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
        systemConfig.put("queryLimitPerMin", 5);
        systemConfig.put("reissueEnabled", true);
        systemConfig.put("reissueIntervalDays", 7);
        systemConfig.put("requestNeedReview", true);
        stationProfile.put("stationCallsign", "");
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
            .map(LinkedHashMap::new)
            .collect(Collectors.toList());
    }

    public Map<String, Object> get(String type, Long id) {
        var item = getStore(type).get(id);
        return item == null ? null : new LinkedHashMap<>(item);
    }

    public Map<String, Object> create(String type, Map<String, Object> payload, String operator) {
        var now = OffsetDateTime.now().toString();
        var id = idGenerator.incrementAndGet();
        var item = new LinkedHashMap<String, Object>();
        item.putAll(payload);
        item.put("id", id);
        item.putIfAbsent("createdAt", now);
        item.put("updatedAt", now);
        item.putIfAbsent("createdBy", operator);
        item.put("updatedBy", operator);
        item.putIfAbsent("deleted", false);

        if ("card".equals(type)) {
            item.putIfAbsent("productionStatus", "DRAFT");
            item.putIfAbsent("sentStatus", "NOT_SENT");
            item.putIfAbsent("receivedStatus", "NOT_RECEIVED");
            item.putIfAbsent("reissueCount", 0);
        }

        if ("address".equals(type)) {
            validateAddressBookUnique(null, payload);
        }
        if ("card".equals(type)) {
            validateCardPayloadForCreate(payload);
        }

        getStore(type).put(id, item);
        writeAudit(type, String.valueOf(id), "create", operator, "success", null, item);
        return new LinkedHashMap<>(item);
    }

    public Map<String, Object> update(String type, Long id, Map<String, Object> payload, String operator) {
        var store = getStore(type);
        var existing = store.get(id);
        if (existing == null || isDeleted(existing)) {
            return null;
        }
        if ("address".equals(type)) {
            validateAddressBookUnique(id, payload, existing);
        }
        if ("card".equals(type)) {
            validateCardPayloadForUpdate(id, payload, existing);
        }

        var before = new LinkedHashMap<>(existing);
        existing.putAll(payload);
        existing.put("updatedAt", OffsetDateTime.now().toString());
        existing.put("updatedBy", operator);
        writeAudit(type, String.valueOf(id), "update", operator, "success", before, existing);
        return new LinkedHashMap<>(existing);
    }

    public boolean softDelete(String type, Long id, String operator) {
        var store = getStore(type);
        var existing = store.get(id);
        if (existing == null || isDeleted(existing)) {
            return false;
        }
        var before = new LinkedHashMap<>(existing);
        existing.put("deleted", true);
        existing.put("deletedAt", OffsetDateTime.now().toString());
        existing.put("deletedBy", operator);
        existing.put("updatedAt", OffsetDateTime.now().toString());
        writeAudit(type, String.valueOf(id), "delete", operator, "success", before, existing);
        return true;
    }

    public Map<String, Object> sendConfirm(Map<String, Object> payload, String operator) {
        var ids = castIds(payload.get("cardIds"));
        var updated = new ArrayList<Map<String, Object>>();
        var batchNo = Objects.toString(payload.getOrDefault("batchNo", "BATCH-" + System.currentTimeMillis()));
        var now = OffsetDateTime.now().toString();
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
            updated.add(new LinkedHashMap<>(card));
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("count", updated.size());
        result.put("items", updated);
        return result;
    }

    public Map<String, Object> receiveConfirm(Map<String, Object> payload, String operator) {
        var ids = castIds(payload.get("cardIds"));
        var updated = new ArrayList<Map<String, Object>>();
        var now = OffsetDateTime.now().toString();
        for (Long id : ids) {
            var card = cardRecords.get(id);
            if (card == null || isDeleted(card)) {
                continue;
            }
            var before = new LinkedHashMap<>(card);
            card.put("receivedStatus", "RECEIVED");
            card.put("receivedAt", now);
            card.put("receivedBy", operator);
            if (payload.containsKey("receiveRemark")) {
                card.put("receiveRemark", payload.get("receiveRemark"));
            }
            card.put("updatedAt", now);
            card.put("updatedBy", operator);
            writeAudit("card", String.valueOf(id), "receive_confirm", operator, "success", before, card);
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
        card.put("updatedAt", OffsetDateTime.now().toString());
        card.put("updatedBy", operator);
        writeAudit("card", String.valueOf(cardId), "reissue_prepare", operator, "success", before, card);
        return Map.of("prepared", true, "card", new LinkedHashMap<>(card));
    }

    public byte[] exportCardsCsv(List<Long> cardIds) {
        var rows = cardIds == null || cardIds.isEmpty()
            ? list("card") : cardIds.stream().map(cardRecords::get).filter(Objects::nonNull).toList();
        var header = "呼号,卡片类型,寄出状态,寄出时间,收到状态,收到时间,补卡次数\n";
        var builder = new StringBuilder(header);
        for (var r : rows) {
            builder.append(csv(r.get("peerCallsign"))).append(',')
                .append(csv(r.get("cardType"))).append(',')
                .append(csv(r.get("sentStatus"))).append(',')
                .append(csv(r.get("sentAt"))).append(',')
                .append(csv(r.get("receivedStatus"))).append(',')
                .append(csv(r.get("receivedAt"))).append(',')
                .append(csv(r.get("reissueCount"))).append('\n');
        }
        return withBom(builder.toString());
    }

    public byte[] exportEnvelopesCsv(List<Long> cardIds) {
        var rows = cardIds == null || cardIds.isEmpty()
            ? list("card") : cardIds.stream().map(cardRecords::get).filter(Objects::nonNull).toList();
        var header = "呼号,姓名,电话,邮编,收件地址\n";
        var builder = new StringBuilder(header);
        for (var r : rows) {
            builder.append(csv(r.get("peerCallsign"))).append(',')
                .append(csv(r.get("name"))).append(',')
                .append(csv(r.get("phone"))).append(',')
                .append(csv(r.get("postcode"))).append(',')
                .append(csv(r.get("address"))).append('\n');
        }
        return withBom(builder.toString());
    }

    public Map<String, Object> backupExport(String operator) {
        var task = create("task", Map.of("taskType", "EXPORT", "status", "COMPLETED", "fileName", "backup.json"), operator);
        return Map.of("task", task, "summary", "backup export simulated");
    }

    public Map<String, Object> backupImport(String operator) {
        var task = create("task", Map.of("taskType", "IMPORT", "status", "COMPLETED", "fileName", "backup.json"), operator);
        return Map.of("task", task, "summary", "backup import simulated");
    }

    public Map<String, Object> approveRequest(Long id, String operator) {
        var request = exchangeRequests.get(id);
        if (request == null || isDeleted(request)) {
            return null;
        }
        var now = OffsetDateTime.now().toString();
        var requestType = Objects.toString(request.getOrDefault("requestType", "NORMAL"));
        if ("NORMAL".equalsIgnoreCase(requestType)) {
            var generated = create("card", Map.of(
                "cardType", "EYEBALL",
                "peerCallsign", request.getOrDefault("bindCallsign", ""),
                "cardDate", OffsetDateTime.now().toLocalDate().toString(),
                "cardTime", OffsetDateTime.now().toLocalTime().withNano(0).toString()
            ), operator);
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
        return update("request", id, Map.of("status", "APPROVED", "reviewedBy", operator, "reviewedAt", now), operator);
    }

    public Map<String, Object> rejectRequest(Long id, String reason, String operator) {
        return update("request", id,
            Map.of("status", "REJECTED", "reviewReason", reason, "reviewedBy", operator,
                "reviewedAt", OffsetDateTime.now().toString()),
            operator);
    }

    public Map<String, Object> approveBinding(Long id, String operator) {
        return update("binding", id,
            Map.of("status", "APPROVED", "reviewedBy", operator, "reviewedAt", OffsetDateTime.now().toString()),
            operator);
    }

    public Map<String, Object> rejectBinding(Long id, String reason, String operator) {
        return update("binding", id,
            Map.of("status", "REJECTED", "reviewReason", reason, "reviewedBy", operator,
                "reviewedAt", OffsetDateTime.now().toString()),
            operator);
    }

    public List<Map<String, Object>> queryCardsByCallsign(String callsign) {
        var keyword = Objects.toString(callsign, "").trim().toLowerCase();
        return list("card").stream()
            .filter(c -> keyword.isBlank() || containsCallsign(c, keyword))
            .map(c -> {
                var item = new LinkedHashMap<String, Object>();
                item.put("id", c.get("id"));
                item.put("peerCallsign", c.get("peerCallsign"));
                item.put("cardType", c.get("cardType"));
                item.put("sentStatus", c.get("sentStatus"));
                item.put("sentAt", c.get("sentAt"));
                item.put("receivedStatus", c.get("receivedStatus"));
                item.put("receivedAt", c.get("receivedAt"));
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
        // Import policy: new value overrides old value; null does not override non-null.
        var qsoImported = importByDedupe("qso", castMapList(payload.get("qsoRecords")),
            List.of("peerCallsign", "qsoDate", "qsoTime", "frequency", "mode"), operator);
        var cardImported = importByDedupe("card", castMapList(payload.get("qslCardRecords")),
            List.of("cardType", "peerCallsign", "cardDate", "cardTime", "qsoRecordId"), operator);
        var addressImported = importByDedupe("address", castMapList(payload.get("addressBooks")),
            List.of("callsign", "address"), operator);

        var task = create("task", Map.of(
            "taskType", "IMPORT",
            "status", "COMPLETED",
            "fileName", "payload-import",
            "summary", "qso=" + qsoImported + ", card=" + cardImported + ", address=" + addressImported), operator);
        return Map.of("task", task, "qsoImported", qsoImported, "cardImported", cardImported, "addressImported", addressImported);
    }

    public Map<String, Object> reportSummary() {
        var cards = list("card");
        var sent = cards.stream().filter(c -> "SENT".equals(c.get("sentStatus"))).count();
        var received = cards.stream().filter(c -> "RECEIVED".equals(c.get("receivedStatus"))).count();
        var pendingPrint = cards.stream().filter(c -> "PENDING_PRINT".equals(c.get("productionStatus"))).count();
        var notSent = cards.stream().filter(c -> "NOT_SENT".equals(c.get("sentStatus"))).count();
        var notReceived = cards.stream().filter(c -> "NOT_RECEIVED".equals(c.get("receivedStatus"))).count();

        var data = new LinkedHashMap<String, Object>();
        data.put("sentCount", sent);
        data.put("receivedCount", received);
        data.put("pendingPrintCount", pendingPrint);
        data.put("pendingSendCount", notSent);
        data.put("pendingReceiveCount", notReceived);
        data.put("total", cards.size());
        return data;
    }

    public List<Map<String, Object>> reportMonthlyTrend() {
        return list("card").stream()
            .collect(Collectors.groupingBy(
                c -> {
                    var d = Objects.toString(c.getOrDefault("cardDate", OffsetDateTime.now().toLocalDate().toString()));
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
        var receivedStatus = Objects.toString(filters.getOrDefault("receivedStatus", "")).trim();
        return list("card").stream()
            .filter(c -> callsign.isBlank() || Objects.toString(c.getOrDefault("peerCallsign", "")).contains(callsign))
            .filter(c -> cardType.isBlank() || cardType.equalsIgnoreCase(Objects.toString(c.getOrDefault("cardType", ""))))
            .filter(c -> productionStatus.isBlank()
                || productionStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("productionStatus", ""))))
            .filter(c -> sentStatus.isBlank() || sentStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("sentStatus", ""))))
            .filter(c -> receivedStatus.isBlank()
                || receivedStatus.equalsIgnoreCase(Objects.toString(c.getOrDefault("receivedStatus", ""))))
            .collect(Collectors.toList());
    }

    public byte[] exportDashboardCsv(Map<String, String> filters) {
        var rows = dashboardOverview(filters);
        var builder = new StringBuilder("呼号,卡片类型,制作状态,寄出状态,收卡状态,发信时间,收信时间,补卡次数\n");
        for (var r : rows) {
            builder.append(csv(r.get("peerCallsign"))).append(',')
                .append(csv(r.get("cardType"))).append(',')
                .append(csv(r.get("productionStatus"))).append(',')
                .append(csv(r.get("sentStatus"))).append(',')
                .append(csv(r.get("receivedStatus"))).append(',')
                .append(csv(r.get("sentAt"))).append(',')
                .append(csv(r.get("receivedAt"))).append(',')
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

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        return Long.parseLong(String.valueOf(value));
    }

    private boolean isDeleted(Map<String, Object> item) {
        return Boolean.TRUE.equals(item.get("deleted"));
    }

    private void writeAudit(String objectType, String objectId, String operation, String operator,
        String result, Map<String, Object> before, Map<String, Object> after) {
        var id = idGenerator.incrementAndGet();
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
        log.put("createdAt", OffsetDateTime.now().toString());
        auditLogs.put(id, log);
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

    private String csv(Object value) {
        var raw = Objects.toString(value, "");
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private byte[] withBom(String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        var result = new byte[bom.length + bytes.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(bytes, 0, result, bom.length, bytes.length);
        return result;
    }
}
