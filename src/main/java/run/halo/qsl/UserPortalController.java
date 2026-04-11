package run.halo.qsl;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/plugins/qsl-management/api")
public class UserPortalController {

    private final QslDataService dataService;

    public UserPortalController(QslDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/me/callsign-bindings")
    public List<Map<String, Object>> myBindings(Principal principal) {
        return dataService.listBindingsByUser(requireAuthenticatedUser(principal));
    }

    @PostMapping("/me/callsign-bindings")
    public Map<String, Object> submitMyBinding(@RequestBody Map<String, Object> payload,
        Principal principal) {
        var userId = requireAuthenticatedUser(principal);
        try {
            return dataService.submitBinding(userId, payload, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/me/callsign-records/search")
    public Map<String, Object> searchMyCallsignRecords(@RequestParam("callsign") String callsign,
        Principal principal) {
        requireAuthenticatedUser(principal);
        return dataService.searchCallsignRecordStats(callsign);
    }

    private String requireAuthenticatedUser(Principal principal) {
        var userName = principal == null ? "" : Objects.toString(principal.getName(), "").trim();
        if (userName.isBlank() || "anonymousUser".equalsIgnoreCase(userName)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        return userName;
    }
}

