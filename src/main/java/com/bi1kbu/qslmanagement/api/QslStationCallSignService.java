package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

@Service
public class QslStationCallSignService {
    public static final String DEFAULT_CALL_SIGN = "BI1KBU";
    private static final String STATION_PROFILE_NAME = "qsl-station-profile-default";
    private final ReactiveExtensionClient client;

    public QslStationCallSignService(ReactiveExtensionClient client) {
        this.client = client;
    }

    public Mono<String> getExampleCallSign() {
        return Mono.defer(() -> client.fetch(StationProfile.class, STATION_PROFILE_NAME))
            .map(profile -> {
                var spec = profile.getSpec();
                var callSign = spec == null ? null : spec.getMyCallSign();
                return callSign == null || callSign.isBlank() ? DEFAULT_CALL_SIGN : callSign.trim();
            })
            .defaultIfEmpty(DEFAULT_CALL_SIGN);
    }
}
