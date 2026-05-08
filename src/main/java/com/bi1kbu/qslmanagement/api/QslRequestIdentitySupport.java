package com.bi1kbu.qslmanagement.api;

import org.springframework.web.reactive.function.server.ServerRequest;

public final class QslRequestIdentitySupport {

    private QslRequestIdentitySupport() {
    }

    public static String resolveClientIp(ServerRequest request) {
        var forwardedFor = request.headers().firstHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            var parts = forwardedFor.split(",");
            if (parts.length > 0 && !parts[0].isBlank()) {
                return parts[0].trim();
            }
        }

        var realIp = request.headers().firstHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }

        return request.remoteAddress()
            .map(address -> address.getAddress().getHostAddress())
            .orElse("unknown");
    }
}
