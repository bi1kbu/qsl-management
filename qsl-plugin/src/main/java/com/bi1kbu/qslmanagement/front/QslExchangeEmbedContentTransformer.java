package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class QslExchangeEmbedContentTransformer {

    private static final Pattern SHORTCODE_PATTERN = Pattern.compile("(?is)\\[(qsl-online-exchange-card|qsl-offline-exchange-card)([^\\]]*)\\]");
    private static final Pattern CALL_SIGN_ATTR_PATTERN = Pattern.compile(
        "(?i)callSign\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern CARD_ID_ATTR_PATTERN = Pattern.compile(
        "(?i)cardId\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern ACTIVITY_ID_ATTR_PATTERN = Pattern.compile(
        "(?i)activityId\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s]+))"
    );
    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");

    public String transform(String content) {
        if (content == null || content.isBlank() || (!content.contains("[qsl-online-exchange-card")
            && !content.contains("[qsl-offline-exchange-card"))) {
            return content;
        }

        var matcher = SHORTCODE_PATTERN.matcher(content);
        var builder = new StringBuilder();
        var prefix = "qsl-exchange-" + UUID.randomUUID().toString().replace("-", "");
        var sequence = 1;
        while (matcher.find()) {
            var shortcodeName = firstNotBlank(matcher.group(1));
            var attributes = matcher.group(2);
            var offlineMode = "qsl-offline-exchange-card".equalsIgnoreCase(shortcodeName);
            var callSign = extractCallSign(attributes);
            var cardId = extractCardId(attributes);
            var activityId = extractActivityId(attributes);
            var embedId = prefix + "-" + sequence++;
            var replacement = buildEmbedBlock(callSign, cardId, activityId, offlineMode, embedId);
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

    private String extractCardId(String attributes) {
        return extractAttributeValue(attributes, CARD_ID_ATTR_PATTERN).trim().toUpperCase();
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

    private String buildEmbedBlock(String callSign, String cardId, String activityId, boolean offlineMode, String embedId) {
        UriComponentsBuilder uriBuilder;
        if (offlineMode) {
            if (!cardId.isBlank()) {
                uriBuilder = UriComponentsBuilder
                    .fromPath("/apis/api.qsl-management.halo.run/v1alpha1/exchange-offline")
                    .pathSegment(cardId);
            } else {
                uriBuilder = UriComponentsBuilder.fromPath("/apis/api.qsl-management.halo.run/v1alpha1/exchange-offline");
                if (!callSign.isBlank()) {
                    uriBuilder.queryParam("cs", callSign);
                }
                if (!activityId.isBlank()) {
                    uriBuilder.queryParam("aid", activityId);
                }
            }
        } else {
            if (!cardId.isBlank()) {
                uriBuilder = UriComponentsBuilder
                    .fromPath("/apis/api.qsl-management.halo.run/v1alpha1/exchange-online")
                    .pathSegment(cardId);
            } else {
                uriBuilder = UriComponentsBuilder
                    .fromPath("/apis/api.qsl-management.halo.run/v1alpha1/exchange-online");
            }
            if (!callSign.isBlank()) {
                uriBuilder.queryParam("cs", callSign);
            }
        }
        uriBuilder.queryParam("embed", "1").queryParam("eid", embedId);
        var src = uriBuilder.build().toUriString();
        var cardTitle = offlineMode ? "线下换卡确认" : "线上换卡申请";

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
            """.formatted(src, embedId, cardTitle);
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
