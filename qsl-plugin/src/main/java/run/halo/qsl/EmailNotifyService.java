package run.halo.qsl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import run.halo.app.notification.NotificationContext;

@Service
public class EmailNotifyService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifyService.class);
    private static final String SENDER_CONFIG_API =
        "http://127.0.0.1:8090/apis/api.console.halo.run/v1alpha1/notifiers/default-email-notifier/sender-config";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QslMailTemplateService templateService;
    private final QslSmtpNotifier smtpNotifier;

    public EmailNotifyService(QslMailTemplateService templateService, QslSmtpNotifier smtpNotifier) {
        this.templateService = templateService;
        this.smtpNotifier = smtpNotifier;
    }

    public Map<String, Object> notifyExchangeRequestReviewed(String toEmail, String callsign, boolean approved,
        String reason, String reviewedAt, Map<String, String> authHeaders) {
        var tpl = templateService.renderReviewTemplate(approved, callsign, reviewedAt, reason);
        return notifyWithTemplate(toEmail, tpl, authHeaders, false);
    }

    public Map<String, Object> notifyCardSendConfirmed(String toEmail, String callsign, String cardId, String sentAt,
        Map<String, String> authHeaders) {
        var tpl = templateService.renderSendConfirmTemplate(callsign, cardId, sentAt);
        return notifyWithTemplate(toEmail, tpl, authHeaders, true);
    }

    public Map<String, Object> notifyCardReceiveConfirmed(String toEmail, String callsign, String cardId,
        String receivedAt, Map<String, String> authHeaders) {
        var tpl = templateService.renderReceiveConfirmTemplate(callsign, cardId, receivedAt);
        return notifyWithTemplate(toEmail, tpl, authHeaders, true);
    }

    private Map<String, Object> notifyWithTemplate(String toEmail, Map<String, String> tpl,
        Map<String, String> authHeaders, boolean skipWhenEmailBlank) {
        var result = new LinkedHashMap<String, Object>();
        var to = Objects.toString(toEmail, "").trim();
        if (to.isBlank()) {
            if (skipWhenEmailBlank) {
                result.put("mailSent", false);
                result.put("mailSkipped", true);
                result.put("mailReason", "email is blank");
            } else {
                result.put("mailSent", false);
                result.put("mailError", "email is blank");
            }
            return result;
        }
        if (authHeaders == null || authHeaders.isEmpty()) {
            result.put("mailSent", false);
            result.put("mailError", "auth headers missing");
            return result;
        }

        try {
            var senderConfig = fetchSenderConfig(authHeaders);
            if (!senderConfig.path("enable").asBoolean(true)) {
                result.put("mailSent", false);
                result.put("mailError", "email notifier disabled");
                return result;
            }

            var context = new NotificationContext();
            var msg = new NotificationContext.Message();
            msg.setRecipient(to);
            msg.setTimestamp(Instant.now());

            var payload = new NotificationContext.MessagePayload();
            payload.setTitle(tpl.get("subject"));
            payload.setRawBody(tpl.get("body"));
            msg.setPayload(payload);
            context.setMessage(msg);
            context.setSenderConfig(senderConfig);

            smtpNotifier.notify(context).block();
            result.put("mailSent", true);
        } catch (Exception ex) {
            log.warn("Failed to send qsl email to {}", to, ex);
            result.put("mailSent", false);
            result.put("mailError", ex.getMessage());
        }
        return result;
    }

    private ObjectNode fetchSenderConfig(Map<String, String> authHeaders) throws Exception {
        var reqBuilder = HttpRequest.newBuilder(URI.create(SENDER_CONFIG_API)).GET();
        copyHeader(authHeaders, reqBuilder, "Cookie");
        copyHeader(authHeaders, reqBuilder, "Authorization");
        copyHeader(authHeaders, reqBuilder, "X-XSRF-TOKEN");
        copyHeader(authHeaders, reqBuilder, "X-CSRF-TOKEN");
        var response = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(
                "fetch sender config failed: HTTP " + response.statusCode() + " " + response.body());
        }
        return (ObjectNode) objectMapper.readTree(response.body());
    }

    private void copyHeader(Map<String, String> headers, HttpRequest.Builder builder, String key) {
        var value = Objects.toString(headers.getOrDefault(key, ""), "").trim();
        if (!value.isBlank()) {
            builder.header(key, value);
        }
    }
}
