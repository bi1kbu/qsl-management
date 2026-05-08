package com.bi1kbu.qslmanagement.front;

import com.bi1kbu.qslmanagement.api.QslApiSupport;
import java.util.regex.Pattern;

final class QslPublicPageRenderSupport {

    private static final Pattern CALL_SIGN_PATTERN = Pattern.compile("^[A-Z0-9/-]{3,16}$");
    private static final Pattern EMBED_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private QslPublicPageRenderSupport() {
    }

    static String renderError(String title, String message, boolean embed) {
        var safeMessage = escapeHtml(message == null || message.isBlank() ? "页面加载失败" : message);
        var safeTitle = embed ? title + "加载失败" : title + "页面加载失败";
        return """
            <!doctype html>
            <html lang="zh-CN">
              <head><meta charset="UTF-8" /><meta name="viewport" content="width=device-width, initial-scale=1.0" />
                <title>%s</title></head>
              <body style="font-family:'PingFang SC','Microsoft YaHei',sans-serif;padding:20px;">
                <h1 style="font-size:18px;margin:0 0 10px;">%s</h1><p style="margin:0;color:#4b5563;">%s</p>
              </body>
            </html>
            """.formatted(safeTitle, safeTitle, safeMessage);
    }

    static String normalizeCallSign(String rawCallSign) {
        var normalized = QslApiSupport.normalizeCallSign(rawCallSign);
        if (!CALL_SIGN_PATTERN.matcher(normalized).matches()) {
            return "";
        }
        return normalized;
    }

    static String normalizeEmbedId(String rawEmbedId) {
        var normalized = rawEmbedId == null ? "" : rawEmbedId.trim();
        if (!EMBED_ID_PATTERN.matcher(normalized).matches()) {
            return "qsl-exchange-default";
        }
        return normalized;
    }

    static String normalizeText(String raw, int maxLength) {
        if (raw == null) {
            return "";
        }
        var text = raw.trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength);
    }

    static String escapeHtml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    static String escapeJs(String value) {
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", "\\r")
            .replace("\n", "\\n");
    }
}
