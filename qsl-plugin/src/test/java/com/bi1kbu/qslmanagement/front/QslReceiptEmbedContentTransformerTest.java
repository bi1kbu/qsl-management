package com.bi1kbu.qslmanagement.front;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QslReceiptEmbedContentTransformerTest {

    private final QslReceiptEmbedContentTransformer transformer = new QslReceiptEmbedContentTransformer();

    @Test
    void shouldKeepContentWhenShortcodeMissing() {
        var content = "普通正文内容";

        var transformed = transformer.transform(content);

        assertEquals(content, transformed);
    }

    @Test
    void shouldReplaceShortcodeWithReceiptEmbedIframe() {
        var content = "前文 [qsl-receipt-card] 后文";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("/apis/api.qsl-management.halo.run/v1alpha1/receipt-public/page?embed=1&embedId=qsl-receipt-card-"));
        assertTrue(transformed.contains("data-qsl-embed-id=\"qsl-receipt-card-"));
        assertTrue(transformed.contains("window.__qslCardEmbedResizeBound"));
    }

    @Test
    void shouldNormalizeCallSignInReceiptShortcode() {
        var content = "[qsl-receipt-card callSign=\"BI1KBU\"]";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("callSign=BI1KBU"));
    }

    @Test
    void shouldIncludeCardIdInReceiptShortcode() {
        var content = "[qsl-receipt-card cardId=\"card-record-001\"]";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("cardId=card-record-001"));
    }
}
