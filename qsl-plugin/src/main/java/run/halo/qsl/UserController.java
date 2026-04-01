package run.halo.qsl;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/apis/qsl.user/v1")
public class UserController {

    private final QslDataService dataService;

    public UserController(QslDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/my/cards")
    public List<Map<String, Object>> myCards(@RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id") String userId) {
        return dataService.queryMyCards(userId, callsign);
    }

    @GetMapping("/my/qso-records")
    public List<Map<String, Object>> myQso(@RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id") String userId) {
        return dataService.queryMyQso(userId, callsign);
    }

    @GetMapping("/my/exchange-requests")
    public List<Map<String, Object>> myRequests() {
        return dataService.list("request");
    }

    @PostMapping("/my/exchange-requests")
    public Map<String, Object> createRequest(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "ham-user") String operator,
        @RequestHeader(value = "X-Role", defaultValue = "HAM") String role) {
        var requestType = Objects.toString(payload.getOrDefault("requestType", "NORMAL")).toUpperCase();
        if (!List.of("NORMAL", "REISSUE").contains(requestType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unsupported requestType");
        }
        if ("NORMAL".equals(requestType)) {
            payload.put("targetCardType", "EYEBALL");
        } else {
            if (!List.of("HAM", "OPERATOR").contains(role.toUpperCase())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "reissue only for HAM or OPERATOR");
            }
            if (!payload.containsKey("qslCardRecordId")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qslCardRecordId is required for REISSUE");
            }
        }
        payload.put("requestType", requestType);
        payload.putIfAbsent("status", "PENDING");
        return dataService.create("request", payload, operator);
    }

    @PostMapping("/my/exchange-requests/{id}/cancel")
    public Map<String, Object> cancelRequest(@PathVariable("id") Long id,
        @RequestHeader(value = "X-Operator", defaultValue = "ham-user") String operator) {
        var updated = dataService.update("request", id, Map.of("status", "CANCELED"), operator);
        return updated == null ? Map.of("canceled", false) : Map.of("canceled", true, "item", updated);
    }

    @GetMapping("/my/callsign-bindings")
    public List<Map<String, Object>> myBindings() {
        return dataService.list("binding");
    }

    @PostMapping("/my/callsign-bindings")
    public Map<String, Object> createBinding(@RequestBody Map<String, Object> payload,
        @RequestHeader(value = "X-Operator", defaultValue = "ham-user") String operator,
        @RequestHeader(value = "X-User-Id") String userId) {
        payload.put("userId", userId);
        var verifyMethod = Objects.toString(payload.getOrDefault("verifyMethod", "EVIDENCE"));
        if ("PHONE".equalsIgnoreCase(verifyMethod)) {
            payload.put("status", "APPROVED");
        } else {
            payload.putIfAbsent("status", "PENDING");
        }
        return dataService.create("binding", payload, operator);
    }
}
