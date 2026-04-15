package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class QslCardEmbedContentTransformer {

    private static final Pattern SHORTCODE_PATTERN = Pattern.compile("(?is)\\[qsl-card([^\\]]*)\\]");
    private static final Pattern CALL_SIGN_ATTR_PATTERN = Pattern.compile(
        "(?i)callSign\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");

    public String transform(String content) {
        if (content == null || content.isBlank() || !content.contains("[qsl-card")) {
            return content;
        }

        var matcher = SHORTCODE_PATTERN.matcher(content);
        var builder = new StringBuilder();
        var prefix = "qsl-card-" + UUID.randomUUID().toString().replace("-", "");
        var sequence = 1;
        while (matcher.find()) {
            var attributes = matcher.group(1);
            var callSign = extractCallSign(attributes);
            var embedId = prefix + "-" + sequence++;
            var replacement = buildEmbedBlock(callSign, embedId);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String extractCallSign(String attributes) {
        if (attributes == null || attributes.isBlank()) {
            return "";
        }
        var matcher = CALL_SIGN_ATTR_PATTERN.matcher(attributes);
        if (!matcher.find()) {
            return "";
        }
        var raw = firstNotBlank(
            matcher.group(2),
            matcher.group(3),
            matcher.group(4)
        );
        var normalized = QslApiSupport.normalizeCallSign(raw);
        if (!CALL_SIGN_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
    }

    private String buildEmbedBlock(String callSign, String embedId) {
        var uriBuilder = UriComponentsBuilder
            .fromPath("/apis/api.qsl-management.halo.run/v1alpha1/cards/page")
            .queryParam("embed", "1")
            .queryParam("embedId", embedId);
        if (!callSign.isBlank()) {
            uriBuilder.queryParam("callSign", callSign);
        }
        var src = uriBuilder.build().toUriString();

        return """
            <div class="qsl-article-card" style="margin: 16px 0;">
              <iframe
                src="%s"
                data-qsl-embed-id="%s"
                style="width: 100%%; min-height: 260px; border: 0; border-radius: 10px; background: #ffffff;"
                loading="lazy"
                referrerpolicy="same-origin"
                title="QSL 卡片查询"
              ></iframe>
            </div>
            <script>
              (function () {
                if (window.__qslCardEmbedResizeBound) {
                  return;
                }
                window.__qslCardEmbedResizeBound = true;
                window.addEventListener("message", function (event) {
                  var data = event.data;
                  if (!data || data.type !== "qsl-card-height" || !data.embedId) {
                    return;
                  }
                  var iframe = document.querySelector('iframe[data-qsl-embed-id="' + data.embedId + '"]');
                  if (!iframe) {
                    return;
                  }
                  var parsedHeight = Number(data.height);
                  if (!Number.isFinite(parsedHeight)) {
                    return;
                  }
                  iframe.style.height = Math.max(260, parsedHeight) + "px";
                });
              })();
            </script>
            """.formatted(src, embedId);
    }

    private String firstNotBlank(String... candidates) {
        if (candidates == null) {
            return "";
        }
        for (var candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "";
    }
}
