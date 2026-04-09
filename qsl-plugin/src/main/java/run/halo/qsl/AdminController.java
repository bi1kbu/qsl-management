package run.halo.qsl;

import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/apis/qsl.admin/v1")
public class AdminController {

    private final QslDataService dataService;

    public AdminController(QslDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/station-profile")
    public Map<String, Object> getStationProfile() {
        return dataService.getStationProfile();
    }

    @PutMapping("/station-profile")
    public Map<String, Object> putStationProfile(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.updateStationProfile(payload, operator);
    }

    @GetMapping("/system-config")
    public Map<String, Object> getSystemConfig() {
        return dataService.getSystemConfig();
    }

    @PutMapping("/system-config")
    public Map<String, Object> putSystemConfig(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.updateSystemConfig(payload, operator);
    }

    @GetMapping("/bureau-configs")
    public List<Map<String, Object>> listBureaus() {
        return dataService.list("bureau");
    }

    @PostMapping("/bureau-configs")
    public Map<String, Object> createBureau(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("bureau", payload, operator);
    }

    @PutMapping("/bureau-configs/{id}")
    public Map<String, Object> updateBureau(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("bureau", id, payload, operator);
    }

    @DeleteMapping("/bureau-configs/{id}")
    public Map<String, Object> deleteBureau(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("bureau", id, operator));
    }

    @GetMapping("/equipments")
    public List<Map<String, Object>> listEquipments() {
        return dataService.list("equipment");
    }

    @PostMapping("/equipments")
    public Map<String, Object> createEquipment(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("equipment", payload, operator);
    }

    @PutMapping("/equipments/{id}")
    public Map<String, Object> updateEquipment(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("equipment", id, payload, operator);
    }

    @DeleteMapping("/equipments/{id}")
    public Map<String, Object> deleteEquipment(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("equipment", id, operator));
    }

    @GetMapping("/antennas")
    public List<Map<String, Object>> listAntennas() {
        return dataService.list("antenna");
    }

    @PostMapping("/antennas")
    public Map<String, Object> createAntenna(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("antenna", payload, operator);
    }

    @PutMapping("/antennas/{id}")
    public Map<String, Object> updateAntenna(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("antenna", id, payload, operator);
    }

    @DeleteMapping("/antennas/{id}")
    public Map<String, Object> deleteAntenna(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("antenna", id, operator));
    }

    @GetMapping("/power-presets")
    public List<Map<String, Object>> listPowers() {
        return dataService.list("power");
    }

    @PostMapping("/power-presets")
    public Map<String, Object> createPower(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("power", payload, operator);
    }

    @PutMapping("/power-presets/{id}")
    public Map<String, Object> updatePower(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("power", id, payload, operator);
    }

    @DeleteMapping("/power-presets/{id}")
    public Map<String, Object> deletePower(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("power", id, operator));
    }

    @GetMapping("/modes")
    public List<Map<String, Object>> listModes() {
        return dataService.list("mode");
    }

    @PostMapping("/modes")
    public Map<String, Object> createMode(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("mode", payload, operator);
    }

    @PutMapping("/modes/{id}")
    public Map<String, Object> updateMode(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("mode", id, payload, operator);
    }

    @DeleteMapping("/modes/{id}")
    public Map<String, Object> deleteMode(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("mode", id, operator));
    }

    @GetMapping("/address-books")
    public List<Map<String, Object>> listAddresses() {
        return dataService.list("address");
    }

    @PostMapping("/address-books")
    public Map<String, Object> createAddress(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("address", payload, operator);
    }

    @PutMapping("/address-books/{id}")
    public Map<String, Object> updateAddress(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("address", id, payload, operator);
    }

    @DeleteMapping("/address-books/{id}")
    public Map<String, Object> deleteAddress(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("address", id, operator));
    }

    @GetMapping("/qso-records")
    public List<Map<String, Object>> listQso() {
        return dataService.list("qso");
    }

    @PostMapping("/qso-records")
    public Map<String, Object> createQso(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("qso", payload, operator);
    }

    @GetMapping("/qso-records/{id}")
    public Map<String, Object> getQso(@PathVariable("id") Long id) {
        return dataService.get("qso", id);
    }

    @PutMapping("/qso-records/{id}")
    public Map<String, Object> updateQso(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("qso", id, payload, operator);
    }

    @DeleteMapping("/qso-records/{id}")
    public Map<String, Object> deleteQso(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("qso", id, operator));
    }

    @GetMapping("/qsl-card-records")
    public List<Map<String, Object>> listCards() {
        return dataService.list("card");
    }

    @PostMapping("/qsl-card-records")
    public Map<String, Object> createCard(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.create("card", payload, operator);
    }

    @GetMapping("/qsl-card-records/{id}")
    public Map<String, Object> getCard(@PathVariable("id") Long id) {
        return dataService.get("card", id);
    }

    @PutMapping("/qsl-card-records/{id}")
    public Map<String, Object> updateCard(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.update("card", id, payload, operator);
    }

    @DeleteMapping("/qsl-card-records/{id}")
    public Map<String, Object> deleteCard(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return Map.of("deleted", dataService.softDelete("card", id, operator));
    }

    @PostMapping("/qsl-card-records/send-confirm")
    public Map<String, Object> sendConfirm(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        return dataService.sendConfirm(payload, operator, headers);
    }

    @PostMapping("/qsl-card-records/receive-confirm")
    public Map<String, Object> receiveConfirm(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        try {
            return dataService.receiveConfirm(payload, operator, headers);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PostMapping("/qsl-card-records/reissue-prepare")
    public Map<String, Object> reissuePrepare(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.reissuePrepare(payload, operator);
    }

    @PostMapping("/exports/cards")
    public ResponseEntity<byte[]> exportCards(@RequestBody(required = false) Map<String, Object> payload) {
        var ids = payload == null ? List.<Long>of() : toLongList(payload.get("cardIds"));
        var body = dataService.exportCardsCsv(ids);
        return csvResponse("qsl-cards.csv", body);
    }

    @PostMapping("/exports/envelopes")
    public ResponseEntity<byte[]> exportEnvelopes(@RequestBody(required = false) Map<String, Object> payload) {
        var ids = payload == null ? List.<Long>of() : toLongList(payload.get("cardIds"));
        var body = dataService.exportEnvelopesCsv(ids);
        return csvResponse("qsl-envelopes.csv", body);
    }

    @PostMapping("/backup/export")
    public ResponseEntity<byte[]> backupExport(
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        var body = dataService.exportFullBackupJson(operator);
        var fileName = "qsl-full-backup-" + System.currentTimeMillis() + ".json";
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body);
    }

    @PostMapping("/backup/import")
    public Map<String, Object> backupImport(@RequestBody(required = false) Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        if (payload == null || payload.isEmpty()) {
            return dataService.backupImport(operator);
        }
        return dataService.importBackupData(payload, operator);
    }

    @PostMapping(value = "/backup/import-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<Map<String, Object>> backupImportFile(@RequestPart("file") FilePart file,
        @RequestPart(value = "dataset", required = false) String dataset,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return DataBufferUtils.join(file.content())
            .flatMap(dataBuffer -> {
                var bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                DataBufferUtils.release(dataBuffer);
                try {
                    return Mono.just(dataService.importBackupFile(file.filename(), bytes, dataset, operator));
                } catch (IllegalArgumentException ex) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage()));
                } catch (Exception ex) {
                    return Mono.error(ex);
                }
            });
    }

    @GetMapping("/import-export-tasks")
    public List<Map<String, Object>> listTasks() {
        return dataService.list("task");
    }

    @GetMapping("/import-export-tasks/{id}")
    public Map<String, Object> getTask(@PathVariable("id") Long id) {
        return dataService.get("task", id);
    }

    @GetMapping("/exchange-requests")
    public List<Map<String, Object>> listRequests() {
        return dataService.list("request");
    }

    @PostMapping("/exchange-requests/{id}/approve")
    public Map<String, Object> approveRequest(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        return dataService.approveRequest(id, operator, headers);
    }

    @PostMapping("/exchange-requests/{id}/reject")
    public Map<String, Object> rejectRequest(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        return dataService.rejectRequest(id, String.valueOf(payload.getOrDefault("reason", "")), operator, headers);
    }

    @GetMapping("/callsign-bindings")
    public List<Map<String, Object>> listBindings() {
        return dataService.list("binding");
    }

    @GetMapping("/my/callsign-bindings")
    public List<Map<String, Object>> listMyBindings(
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return dataService.listBindingsByUser(resolveUserId(userId));
    }

    @PostMapping("/my/callsign-bindings")
    public Map<String, Object> submitMyBinding(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Operator", defaultValue = "console-user") String operator) {
        try {
            return dataService.submitBinding(resolveUserId(userId), payload, operator);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/my/callsign-records/search")
    public Map<String, Object> searchMyCallsignRecords(
        @RequestParam("callsign") String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        // userId kept for audit/extendability.
        resolveUserId(userId);
        return dataService.searchCallsignRecordStats(callsign);
    }

    @GetMapping("/my/address-books")
    public List<Map<String, Object>> listMyAddresses(
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return dataService.listAddressesByBoundUser(resolveUserId(userId), callsign);
    }

    @PostMapping("/my/address-books")
    public Map<String, Object> createMyAddress(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Operator", defaultValue = "console-user") String operator) {
        try {
            return dataService.createAddressByBoundUser(resolveUserId(userId), payload, operator);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @PutMapping("/my/address-books/{id}")
    public Map<String, Object> updateMyAddress(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Operator", defaultValue = "console-user") String operator) {
        try {
            return dataService.updateAddressByBoundUser(resolveUserId(userId), id, payload, operator);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/my/address-books/{id}")
    public Map<String, Object> deleteMyAddress(@PathVariable("id") Long id,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-Operator", defaultValue = "console-user") String operator) {
        try {
            return Map.of("deleted", dataService.deleteAddressByBoundUser(resolveUserId(userId), id, operator));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/my/qso-records")
    public List<Map<String, Object>> listMyQso(
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return dataService.queryMyQsoByBoundUser(resolveUserId(userId), callsign);
    }

    @GetMapping("/my/qsl-card-records")
    public List<Map<String, Object>> listMyCards(
        @RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
        return dataService.queryMyCardsByBoundUser(resolveUserId(userId), callsign);
    }

    @PostMapping("/callsign-bindings/{id}/approve")
    public Map<String, Object> approveBinding(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        return dataService.approveBinding(id, operator, headers);
    }

    @PostMapping("/callsign-bindings/{id}/reject")
    public Map<String, Object> rejectBinding(@PathVariable("id") Long id, @RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator) {
        return dataService.rejectBinding(id, String.valueOf(payload.getOrDefault("reason", "")), operator);
    }

    @PostMapping("/callsign-bindings/{id}/unbind")
    public Map<String, Object> unbindBinding(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "admin") String operator,
        @RequestHeader(value = "Cookie", required = false) String cookie,
        @RequestHeader(value = "Authorization", required = false) String authorization,
        @RequestHeader(value = "X-XSRF-TOKEN", required = false) String xsrfToken,
        @RequestHeader(value = "X-CSRF-TOKEN", required = false) String csrfToken) {
        var headers = new java.util.LinkedHashMap<String, String>();
        if (cookie != null) headers.put("Cookie", cookie);
        if (authorization != null) headers.put("Authorization", authorization);
        if (xsrfToken != null) headers.put("X-XSRF-TOKEN", xsrfToken);
        if (csrfToken != null) headers.put("X-CSRF-TOKEN", csrfToken);
        try {
            return dataService.unbindBinding(id, operator, headers);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/audit-logs")
    public List<Map<String, Object>> auditLogs(@RequestParam Map<String, String> query) {
        return dataService.filterAudit(query);
    }

    @GetMapping("/reports/summary")
    public Map<String, Object> reportSummary() {
        return dataService.reportSummary();
    }

    @GetMapping("/reports/trend/monthly")
    public List<Map<String, Object>> reportMonthlyTrend() {
        return dataService.reportMonthlyTrend();
    }

    @GetMapping("/reports/card-type-distribution")
    public List<Map<String, Object>> reportTypeDistribution() {
        return dataService.reportCardTypeDistribution();
    }

    @GetMapping("/dashboard/overview")
    public List<Map<String, Object>> dashboardOverview(@RequestParam Map<String, String> filters) {
        return dataService.dashboardOverview(filters);
    }

    @GetMapping("/dashboard/export")
    public ResponseEntity<byte[]> dashboardExport(@RequestParam Map<String, String> filters) {
        var body = dataService.exportDashboardCsv(filters);
        return csvResponse("qsl-dashboard.csv", body);
    }

    private ResponseEntity<byte[]> csvResponse(String fileName, byte[] body) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(fileName).build().toString())
            .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .body(body);
    }

    private List<Long> toLongList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> values) {
            return values.stream().map(v -> Long.parseLong(String.valueOf(v))).toList();
        }
        return List.of(Long.parseLong(String.valueOf(value)));
    }

    private String resolveUserId(String userId) {
        var resolved = userId == null ? "" : userId.trim();
        if (resolved.isBlank()) {
            return "console-user";
        }
        return resolved;
    }
}
