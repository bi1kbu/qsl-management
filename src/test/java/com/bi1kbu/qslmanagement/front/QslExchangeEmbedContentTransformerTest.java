package com.bi1kbu.qslmanagement.front;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QslExchangeEmbedContentTransformerTest {

    private final QslExchangeEmbedContentTransformer transformer = new QslExchangeEmbedContentTransformer();

    @Test
    void shouldUseOnlineEyeballShortPath() {
        var transformed = transformer.transform(
            "[qsl-online-exchange-card callSign=BI1KBU cardId=C1001]"
        );

        assertTrue(transformed.contains("/online_eyeball/C1001?cs=BI1KBU&embed=1&eid=qsl-exchange-"));
    }

    @Test
    void shouldUseOfflineEyeballShortPath() {
        var transformed = transformer.transform(
            "[qsl-offline-exchange-card callSign=BI1KBU activityId=ACT001]"
        );

        assertTrue(transformed.contains("/eyeball?cs=BI1KBU&aid=ACT001&embed=1&eid=qsl-exchange-"));
    }
}
