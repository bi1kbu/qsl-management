package run.halo.qsl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class QslMailTemplateService {

    private String readTemplate(String path) {
        try (var in = openTemplateStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read template: " + path, ex);
        }
    }

    private InputStream openTemplateStream(String path) throws IOException {
        try {
            return new ClassPathResource(path).getInputStream();
        } catch (Exception ignored) {
            // ignore and fallback
        }
        var withSlash = path.startsWith("/") ? path : "/" + path;
        var in = QslMailTemplateService.class.getResourceAsStream(withSlash);
        if (in != null) {
            return in;
        }
        var cl = Thread.currentThread().getContextClassLoader();
        if (cl != null) {
            var raw = cl.getResourceAsStream(path);
            if (raw != null) {
                return raw;
            }
        }
        throw new IOException("template not found: " + path);
    }

    public Map<String, String> renderReviewTemplate(boolean approved, String callsign, String reviewedAt, String reason) {
        var subjectPath = approved
            ? "templates/mail/exchange-approved-subject.txt"
            : "templates/mail/exchange-rejected-subject.txt";
        var bodyPath = approved
            ? "templates/mail/exchange-approved-body.txt"
            : "templates/mail/exchange-rejected-body.txt";
        var vars = new LinkedHashMap<String, String>();
        vars.put("callsign", Objects.toString(callsign, ""));
        vars.put("reviewedAt", Objects.toString(reviewedAt, ""));
        vars.put("reason", Objects.toString(reason, "未填写"));
        var subject = apply(readTemplate(subjectPath), vars);
        var body = apply(readTemplate(bodyPath), vars);
        return Map.of("subject", subject, "body", body);
    }

    private String apply(String content, Map<String, String> vars) {
        var result = content;
        for (var entry : vars.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", Objects.toString(entry.getValue(), ""));
        }
        return result;
    }
}
