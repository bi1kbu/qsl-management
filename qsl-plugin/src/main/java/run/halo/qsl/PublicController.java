package run.halo.qsl;

import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/apis/qsl.public/v1")
public class PublicController {

    private final QslDataService dataService;
    private final RateLimitService rateLimitService;

    public PublicController(QslDataService dataService, RateLimitService rateLimitService) {
        this.dataService = dataService;
        this.rateLimitService = rateLimitService;
    }

    @GetMapping("/query/cards")
    public List<Map<String, Object>> queryCards(@RequestParam(value = "callsign", required = false) String callsign,
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        ServerWebExchange exchange) {
        var limit = ((Number) dataService.getSystemConfig().getOrDefault("queryLimitPerMin", 5)).intValue();
        var remoteIp = exchange.getRequest().getRemoteAddress() == null
            ? "unknown" : String.valueOf(exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
        var rateKey = (userId != null && !userId.isBlank())
            ? "USER:" + userId : "IP:" + remoteIp;
        if (!rateLimitService.allow(rateKey, limit)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "query rate limit exceeded");
        }
        return dataService.queryCardsByCallsign(callsign);
    }

    @GetMapping("/reports/public-summary")
    public Map<String, Object> publicSummary() {
        return dataService.reportSummary();
    }

    @GetMapping("/reports/public-trend/monthly")
    public List<Map<String, Object>> publicMonthlyTrend() {
        return dataService.reportMonthlyTrend();
    }

    @GetMapping("/reports/public-card-type-distribution")
    public List<Map<String, Object>> publicCardTypeDistribution() {
        return dataService.reportCardTypeDistribution();
    }
}
