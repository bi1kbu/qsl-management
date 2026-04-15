package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslPublicRateLimitServiceTest {

    @Test
    void shouldRejectWhenExceededWithinOneMinuteWindow() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        var service = new QslPublicRateLimitService(client, clock);

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(buildSystemSetting(2)));

        service.checkLimit("qso-public-records", "127.0.0.1").block();
        service.checkLimit("qso-public-records", "127.0.0.1").block();

        var error = assertThrows(QslApiException.class,
            () -> service.checkLimit("qso-public-records", "127.0.0.1").block());
        assertEquals("QSL-429-0001", error.getCode());
        assertEquals(429, error.getStatus().value());
    }

    @Test
    void shouldResetCounterAfterWindowRolled() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        var service = new QslPublicRateLimitService(client, clock);

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(buildSystemSetting(1)));

        service.checkLimit("qso-public-records", "127.0.0.1").block();
        assertThrows(QslApiException.class,
            () -> service.checkLimit("qso-public-records", "127.0.0.1").block());

        clock.plusSeconds(61);
        service.checkLimit("qso-public-records", "127.0.0.1").block();
    }

    @Test
    void shouldFallbackToDefaultLimitWhenSettingMissing() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var clock = new MutableClock(Instant.parse("2026-04-15T00:00:00Z"));
        var service = new QslPublicRateLimitService(client, clock);

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.empty());

        for (int i = 0; i < 30; i++) {
            service.checkLimit("qso-public-records", "127.0.0.1").block();
        }
        assertThrows(QslApiException.class,
            () -> service.checkLimit("qso-public-records", "127.0.0.1").block());
    }

    private SystemSetting buildSystemSetting(int guestQueryPerMinute) {
        var setting = new SystemSetting();
        setting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setGuestQueryPerMinute(guestQueryPerMinute);
        spec.setRequiresExchangeReview(Boolean.TRUE);
        setting.setSpec(spec);
        return setting;
    }

    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zoneId;

        private MutableClock(Instant instant) {
            this(instant, ZoneOffset.UTC);
        }

        private MutableClock(Instant instant, ZoneId zoneId) {
            this.instant = instant;
            this.zoneId = zoneId;
        }

        @Override
        public ZoneId getZone() {
            return zoneId;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void plusSeconds(long seconds) {
            instant = instant.plusSeconds(seconds);
        }
    }
}
