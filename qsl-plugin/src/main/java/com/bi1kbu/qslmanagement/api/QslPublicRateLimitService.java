package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslPublicRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(QslPublicRateLimitService.class);
    private static final int DEFAULT_LIMIT_PER_MINUTE = 30;
    private static final int LIMIT_CACHE_SECONDS = 30;
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";

    private final ReactiveExtensionClient client;
    private final Clock clock;
    private final Map<String, CounterWindow> counterWindows = new ConcurrentHashMap<>();
    private final AtomicReference<LimitCacheEntry> limitCache = new AtomicReference<>(
        new LimitCacheEntry(DEFAULT_LIMIT_PER_MINUTE, Instant.EPOCH)
    );
    private final AtomicLong lastCleanupMinute = new AtomicLong(Long.MIN_VALUE);

    public QslPublicRateLimitService(ReactiveExtensionClient client) {
        this(client, Clock.systemUTC());
    }

    QslPublicRateLimitService(ReactiveExtensionClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    public Mono<Void> checkLimit(String endpointKey, String clientIp) {
        var safeEndpointKey = sanitizeEndpointKey(endpointKey);
        var safeClientIp = sanitizeClientIp(clientIp);
        return resolveLimitPerMinute()
            .flatMap(limitPerMinute -> {
                if (tryAcquire(safeEndpointKey, safeClientIp, limitPerMinute)) {
                    return Mono.empty();
                }
                return Mono.error(new QslApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "QSL-429-0001",
                    "请求过于频繁，请稍后再试"
                ));
            });
    }

    private Mono<Integer> resolveLimitPerMinute() {
        var now = Instant.now(clock);
        var cacheEntry = limitCache.get();
        if (now.isBefore(cacheEntry.expireAt())) {
            return Mono.just(cacheEntry.limitPerMinute());
        }

        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(systemSetting -> extractLimit(systemSetting, DEFAULT_LIMIT_PER_MINUTE))
            .defaultIfEmpty(DEFAULT_LIMIT_PER_MINUTE)
            .onErrorResume(error -> {
                log.warn("读取系统参数失败，使用默认游客限流值。message={}", error.getMessage());
                return Mono.just(DEFAULT_LIMIT_PER_MINUTE);
            })
            .map(this::normalizeLimit)
            .doOnNext(limitPerMinute -> limitCache.set(new LimitCacheEntry(
                limitPerMinute,
                Instant.now(clock).plusSeconds(LIMIT_CACHE_SECONDS)
            )));
    }

    private int extractLimit(SystemSetting setting, int fallbackValue) {
        if (setting == null || setting.getSpec() == null || setting.getSpec().getGuestQueryPerMinute() == null) {
            return fallbackValue;
        }
        return setting.getSpec().getGuestQueryPerMinute();
    }

    private int normalizeLimit(int rawValue) {
        if (rawValue <= 0) {
            return 1;
        }
        if (rawValue > 10000) {
            return 10000;
        }
        return rawValue;
    }

    private boolean tryAcquire(String endpointKey, String clientIp, int limitPerMinute) {
        var nowMinute = Instant.now(clock).getEpochSecond() / 60;
        cleanupStaleWindows(nowMinute);
        var mapKey = endpointKey + "|" + clientIp;
        var updated = counterWindows.compute(mapKey, (ignored, current) -> {
            if (current == null || current.windowMinute() != nowMinute) {
                return new CounterWindow(nowMinute, 1);
            }
            return new CounterWindow(nowMinute, current.count() + 1);
        });
        return updated != null && updated.windowMinute() == nowMinute && updated.count() <= limitPerMinute;
    }

    private void cleanupStaleWindows(long nowMinute) {
        var previousCleanupMinute = lastCleanupMinute.get();
        if (previousCleanupMinute == nowMinute) {
            return;
        }
        if (!lastCleanupMinute.compareAndSet(previousCleanupMinute, nowMinute)) {
            return;
        }
        var expiredMinute = nowMinute - 1;
        counterWindows.entrySet().removeIf(entry -> entry.getValue().windowMinute() < expiredMinute);
    }

    private String sanitizeEndpointKey(String endpointKey) {
        if (endpointKey == null || endpointKey.isBlank()) {
            return "public-endpoint";
        }
        return endpointKey.trim().toLowerCase();
    }

    private String sanitizeClientIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) {
            return "unknown";
        }
        return clientIp.trim().toLowerCase();
    }

    private record CounterWindow(long windowMinute, int count) {
    }

    private record LimitCacheEntry(int limitPerMinute, Instant expireAt) {
    }
}
