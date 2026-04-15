package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetSocketAddress;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;

class QslRequestIdentitySupportTest {

    @Test
    void shouldUseFirstForwardedForAddress() {
        var request = MockServerRequest.builder()
            .header("X-Forwarded-For", "203.0.113.10, 10.0.0.2")
            .build();

        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        assertEquals("203.0.113.10", clientIp);
    }

    @Test
    void shouldFallbackToXRealIpWhenNoForwardedFor() {
        var request = MockServerRequest.builder()
            .header("X-Real-IP", "198.51.100.25")
            .build();

        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        assertEquals("198.51.100.25", clientIp);
    }

    @Test
    void shouldFallbackToRemoteAddressWhenNoProxyHeaders() {
        var request = MockServerRequest.builder()
            .remoteAddress(new InetSocketAddress("127.0.0.1", 8080))
            .build();

        var clientIp = QslRequestIdentitySupport.resolveClientIp(request);
        assertEquals("127.0.0.1", clientIp);
    }
}
