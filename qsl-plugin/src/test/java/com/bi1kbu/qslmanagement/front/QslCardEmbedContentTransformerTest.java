package com.bi1kbu.qslmanagement.front;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QslCardEmbedContentTransformerTest {

    private final QslCardEmbedContentTransformer transformer = new QslCardEmbedContentTransformer();

    @Test
    void shouldKeepContentWhenShortcodeMissing() {
        var content = "普通正文内容";

        var transformed = transformer.transform(content);

        assertEquals(content, transformed);
    }

    @Test
    void shouldReplaceShortcodeWithEmbedIframe() {
        var content = "前文 [qsl-card] 后文";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("/apis/api.qsl-management.halo.run/v1alpha1/cards/page?embed=1&eid=qsl-card-"));
        assertTrue(transformed.contains("data-qsl-embed-id=\"qsl-card-"));
        assertTrue(transformed.contains("window.__qslCardEmbedResizeBound"));
    }

    @Test
    void shouldNormalizeCallSignInShortcode() {
        var content = "[qsl-card callSign=\"BI1KBU\"]";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("cs=BI1KBU"));
    }

    @Test
    void shouldIgnoreInvalidCallSignInShortcode() {
        var content = "[qsl-card callSign=\"!@#\"]";

        var transformed = transformer.transform(content);

        assertTrue(transformed.contains("/cards/page?embed=1&eid=qsl-card-"));
        assertTrue(!transformed.contains("cs="));
    }
}
