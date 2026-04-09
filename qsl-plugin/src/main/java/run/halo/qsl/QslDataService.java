package run.halo.qsl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class QslDataService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};

    private final AtomicLong idGenerator = new AtomicLong(1000);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EmailNotifyService emailNotifyService;

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
        systemConfig.put("queryLimitPerMin", 5);
        systemConfig.put("reissueEnabled", true);
        systemConfig.put("reissueIntervalDays", 7);
        systemConfig.put("requestNeedReview", true);
        stationProfile.put("stationCallsign", "");
    }

    @Autowired
    public QslDataService(EmailNotifyService emailNotifyService) {
        this.emailNotifyService = emailNotifyService;
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
            .map(item -> {
                var copy = new LinkedHashMap<>(item);
                if ("card".equals(type)) {
                    ensureCardStatusDefaults(copy);
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
        }
        return copy;
    }

    public Map<String, Object> create(String type, Map<String, Object> payload, String operator) {
        var now = nowString();
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
        if ("card".equals(type)) {
            if (!payload.containsKey("confirmStatus") && payload.containsKey("receivedStatus")) {
                var legacyReceived = Objects.toString(payload.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                existing.put("confirmStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "CONFIRMED" : "UNCONFIRMED");
            }
            if (!payload.containsKey("returnCardStatus") && payload.containsKey("receivedStatus")) {
                var legacyReceived = Objects.toString(payload.getOrDefault("receivedStatus", "NOT_RECEIVED"));
                existing.put("returnCardStatus", "RECEIVED".equalsIgnoreCase(legacyReceived) ? "RECEIVED" : "NOT_RECEIVED");
            }
            ensureCardStatusDefaults(existing);
        }
        existing.put("updatedAt", nowString());
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
            createPayload.put("name", request.getOrDefault("name", ""));
            createPayload.put("postcode", request.getOrDefault("postcode", ""));
            createPayload.put("address", request.getOrDefault("address", ""));
            createPayload.put("phone", request.getOrDefault("phone", ""));
            createPayload.put("email", request.getOrDefault("email", ""));
            createPayload.put("productionStatus", "PENDING_PRINT");
            createPayload.put("sentStatus", "NOT_SENT");
            createPayload.put("confirmStatus", "UNCONFIRMED");
            createPayload.put("returnCardStatus", "NOT_RECEIVED");
            createPayload.put("cardDate", nowDateString());
            createPayload.put("cardTime", nowTimeString());
            createPayload.put("timezone", "UTC+8");
            createPayload.put("remark", "request-approved");
            var generated = create("card", createPayload, operator);
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
        return update("binding", id,
            Map.of("status", "APPROVED", "reviewedBy", operator, "reviewedAt", nowString()),
            operator);
    }

    private Map<String, Object> sendReviewMail(Map<String, Object> request, boolean approved, String reason,
        String reviewedAt,
        Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        return emailNotifyService.notifyExchangeRequestReviewed(
            Objects.toString(request.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(request.getOrDefault("bindCallsign", "")),
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
        return emailNotifyService.notifyCardSendConfirmed(
            Objects.toString(card.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(card.getOrDefault("peerCallsign", "")),
            Objects.toString(card.getOrDefault("id", "")),
            Objects.toString(card.getOrDefault("sentAt", "")),
            authHeaders == null ? Map.of() : authHeaders
        );
    }

    private Map<String, Object> sendCardReceiveConfirmMail(Map<String, Object> card, Map<String, String> authHeaders) {
        if (emailNotifyService == null) {
            return Map.of("mailSent", false, "mailError", "mail service not initialized");
        }
        return emailNotifyService.notifyCardReceiveConfirmed(
            Objects.toString(card.getOrDefault("email", "")),
            stationCallsignForMail(),
            Objects.toString(card.getOrDefault("peerCallsign", "")),
            Objects.toString(card.getOrDefault("id", "")),
            Objects.toString(card.getOrDefault("receivedAt", card.getOrDefault("returnedAt", ""))),
            authHeaders == null ? Map.of() : authHeaders
        );
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
        log.put("createdAt", nowString());
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
        var root = objectMapper.readTree(text);
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
            cardRows.add(mapCardRow(row, true));
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
        var text = csvText;
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
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

    private Map<String, Object> mapAddressRow(Map<String, Object> row) {
        var mapped = new LinkedHashMap<String, Object>();
        mapped.put("callsign", pick(row, "callsign", "peerCallsign", "对方呼号"));
        mapped.put("address", pick(row, "address", "收件地址", "邮寄地址"));
        mapped.put("name", pick(row, "name", "姓名"));
        mapped.put("phone", pick(row, "phone", "电话"));
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

    private byte[] withBom(String content) {
        var bytes = content.getBytes(StandardCharsets.UTF_8);
        var bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        var result = new byte[bom.length + bytes.length];
        System.arraycopy(bom, 0, result, 0, bom.length);
        System.arraycopy(bytes, 0, result, bom.length, bytes.length);
        return result;
    }
}
