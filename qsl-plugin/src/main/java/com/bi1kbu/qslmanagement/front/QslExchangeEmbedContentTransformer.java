package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class QslExchangeEmbedContentTransformer {

    private static final Pattern SHORTCODE_PATTERN = Pattern.compile("(?is)\\[qsl-exchange-card([^\\]]*)\\]");
    private static final Pattern CALL_SIGN_ATTR_PATTERN = Pattern.compile(
        "(?i)callSign\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern SCENE_TYPE_ATTR_PATTERN = Pattern.compile(
        "(?i)sceneType\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern CARD_ID_ATTR_PATTERN = Pattern.compile(
        "(?i)cardId\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern ACTIVITY_ID_ATTR_PATTERN = Pattern.compile(
        "(?i)activityId\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");

    public String transform(String content) {
        if (content == null || content.isBlank() || !content.contains("[qsl-exchange-card")) {
            return content;
        }

        var matcher = SHORTCODE_PATTERN.matcher(content);
        var builder = new StringBuilder();
        var prefix = "qsl-exchange-card-" + UUID.randomUUID().toString().replace("-", "");
        var sequence = 1;
        while (matcher.find()) {
            var attributes = matcher.group(1);
            var callSign = extractCallSign(attributes);
            var sceneType = extractSceneType(attributes);
            var cardId = extractCardId(attributes);
            var activityId = extractActivityId(attributes);
            var embedId = prefix + "-" + sequence++;
            var replacement = buildEmbedBlock(callSign, cardId, activityId, sceneType, embedId);
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private String extractCallSign(String attributes) {
        var raw = extractAttributeValue(attributes, CALL_SIGN_ATTR_PATTERN);
        var normalized = QslApiSupport.normalizeCallSign(raw);
        if (!CALL_SIGN_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
    }

    private String extractSceneType(String attributes) {
        var raw = extractAttributeValue(attributes, SCENE_TYPE_ATTR_PATTERN).trim().toUpperCase(Locale.ROOT);
        return "EYEBALL".equals(raw) ? "EYEBALL" : "ONLINE_EYEBALL";
    }

    private String extractCardId(String attributes) {
        return extractAttributeValue(attributes, CARD_ID_ATTR_PATTERN).trim().toUpperCase(Locale.ROOT);
    }

    private String extractActivityId(String attributes) {
        return extractAttributeValue(attributes, ACTIVITY_ID_ATTR_PATTERN).trim();
    }

    private String extractAttributeValue(String attributes, Pattern pattern) {
        if (attributes == null || attributes.isBlank()) {
            return "";
        }
        var matcher = pattern.matcher(attributes);
        if (!matcher.find()) {
            return "";
        }
        return firstNotBlank(matcher.group(2), matcher.group(3), matcher.group(4));
    }

    private String buildEmbedBlock(String callSign, String cardId, String activityId, String sceneType, String embedId) {
        var uriBuilder = UriComponentsBuilder
            .fromPath("/apis/api.qsl-management.halo.run/v1alpha1/exchange-public/page")
            .queryParam("embed", "1")
            .queryParam("embedId", embedId)
            .queryParam("sceneType", sceneType);
        if (!callSign.isBlank()) {
            uriBuilder.queryParam("callSign", callSign);
        }
        if (!cardId.isBlank()) {
            uriBuilder.queryParam("cardId", cardId);
        }
        if (!activityId.isBlank()) {
            uriBuilder.queryParam("activityId", activityId);
        }
        var src = uriBuilder.build().toUriString();

        return """
            <div class="qsl-article-card" style="margin: 16px 0;">
              <iframe
                src="%s"
                data-qsl-embed-id="%s"
                style="width: 100%%; min-height: 300px; border: 0; border-radius: 10px; background: transparent;"
                loading="lazy"
                referrerpolicy="same-origin"
                title="%s"
              ></iframe>
            </div>
            <script>
              (function () {
                if (window.__qslCardEmbedResizeBound) { return; }
                window.__qslCardEmbedResizeBound = true;
                window.addEventListener("message", function (event) {
                  var data = event.data;
                  if (!data || data.type !== "qsl-card-height" || !data.embedId) { return; }
                  var iframe = document.querySelector('iframe[data-qsl-embed-id="' + data.embedId + '"]');
                  if (!iframe) { return; }
                  var parsedHeight = Number(data.height);
                  if (!Number.isFinite(parsedHeight)) { return; }
                  iframe.style.height = Math.max(300, parsedHeight) + "px";
                });
              })();
            </script>
            """.formatted(src, sceneType.equals("EYEBALL") ? "线下换卡确认" : "线上换卡申请", embedId);
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
