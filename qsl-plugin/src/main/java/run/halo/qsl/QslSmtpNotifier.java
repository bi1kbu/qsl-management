package run.halo.qsl;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Properties;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.notification.NotificationContext;
import run.halo.app.notification.ReactiveNotifier;

@Component
@Extension
public class QslSmtpNotifier implements ReactiveNotifier {

    @Override
    public Mono<Void> notify(NotificationContext context) {
        return Mono.fromRunnable(() -> doSend(context));
    }

    private void doSend(NotificationContext context) {
        if (context == null || context.getMessage() == null || context.getMessage().getPayload() == null) {
            throw new IllegalArgumentException("notification context is invalid");
        }
        var message = context.getMessage();
        var recipient = Objects.toString(message.getRecipient(), "").trim();
        if (recipient.isBlank()) {
            throw new IllegalArgumentException("recipient is blank");
        }

        var cfg = context.getSenderConfig();
        if (cfg == null) {
            throw new IllegalArgumentException("sender config is missing");
        }

        var host = text(cfg, "host");
        var username = text(cfg, "username");
        var password = text(cfg, "password");
        var sender = text(cfg, "sender");
        var displayName = text(cfg, "displayName");
        var encryption = text(cfg, "encryption").toUpperCase();
        var port = cfg.has("port") ? cfg.path("port").asInt(25) : 25;

        if (host.isBlank() || username.isBlank() || password.isBlank()) {
            throw new IllegalArgumentException("smtp sender config incomplete");
        }
        if (sender.isBlank()) {
            sender = username;
        }

        var props = new Properties();
        props.put("mail.smtp.host", host);
        props.put("mail.smtp.port", String.valueOf(port));
        props.put("mail.smtp.auth", "true");
        if ("SSL".equals(encryption)) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if ("STARTTLS".equals(encryption) || "TLS".equals(encryption)) {
            props.put("mail.smtp.starttls.enable", "true");
        }

        var auth = new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(username, password);
            }
        };

        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(QslSmtpNotifier.class.getClassLoader());
        try {
            var session = Session.getInstance(props, auth);
            var mime = new MimeMessage(session);
            if (displayName.isBlank()) {
                mime.setFrom(new InternetAddress(sender));
            } else {
                mime.setFrom(new InternetAddress(sender, displayName, StandardCharsets.UTF_8.name()));
            }
            mime.setRecipients(Message.RecipientType.TO, InternetAddress.parse(recipient));
            mime.setSubject(Objects.toString(message.getPayload().getTitle(), ""), StandardCharsets.UTF_8.name());
            var html = Objects.toString(message.getPayload().getHtmlBody(), "").trim();
            var raw = Objects.toString(message.getPayload().getRawBody(), "").trim();
            if (!html.isBlank()) {
                mime.setContent(html, "text/html; charset=UTF-8");
            } else {
                mime.setText(raw, StandardCharsets.UTF_8.name());
            }
            Transport.send(mime);
        } catch (Exception ex) {
            throw new IllegalStateException("smtp send failed: " + ex.getMessage(), ex);
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private String text(ObjectNode node, String key) {
        if (node == null || !node.has(key) || node.get(key).isNull()) {
            return "";
        }
        return node.path(key).asText("");
    }
}
