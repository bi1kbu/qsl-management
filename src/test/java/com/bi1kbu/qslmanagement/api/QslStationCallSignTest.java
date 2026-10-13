package com.bi1kbu.qslmanagement.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.bi1kbu.qslmanagement.api.publicapi.QslStationCallSignPublicEndpoint;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.front.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslStationCallSignTest {
    private static final String PROFILE_NAME = "qsl-station-profile-default";
    private final ReactiveExtensionClient storage = mock(ReactiveExtensionClient.class);
    private final QslStationCallSignService service = new QslStationCallSignService(storage);

    @Test
    void shouldUseDefaultForMissingOrBlankProfile() {
        when(storage.fetch(StationProfile.class, PROFILE_NAME)).thenReturn(Mono.empty());
        assertThat(service.getExampleCallSign().block()).isEqualTo("BI1KBU");
        var profile = new StationProfile();
        when(storage.fetch(StationProfile.class, PROFILE_NAME)).thenReturn(Mono.just(profile));
        assertThat(service.getExampleCallSign().block()).isEqualTo("BI1KBU");
        var spec = new StationProfile.StationProfileSpec();
        profile.setSpec(spec);
        for (var value : new String[] {null, "", "   "}) {
            spec.setMyCallSign(value);
            assertThat(service.getExampleCallSign().block()).isEqualTo("BI1KBU");
        }
    }

    @Test
    void shouldReadUpdatedConfigurationOnEverySubscription() {
        when(storage.fetch(StationProfile.class, PROFILE_NAME)).thenReturn(Mono.just(profile(" BG1ABC ")));
        var result = service.getExampleCallSign();
        assertThat(result.block()).isEqualTo("BG1ABC");
        when(storage.fetch(StationProfile.class, PROFILE_NAME)).thenReturn(Mono.just(profile("BA1XYZ")));
        assertThat(result.block()).isEqualTo("BA1XYZ");
        verify(storage, times(2)).fetch(StationProfile.class, PROFILE_NAME);
    }

    @Test
    void shouldReturnOnlyCallSignAndRejectWrites() {
        var profile = profile("BG1ABC");
        profile.getSpec().setMyName("不公开姓名");
        profile.getSpec().setMyAddress("不公开地址");
        profile.getSpec().setMyTelephone("不公开电话");
        profile.getSpec().setMyEmail("private@example.com");
        when(storage.fetch(StationProfile.class, PROFILE_NAME)).thenReturn(Mono.just(profile));
        var limit = mock(QslPublicRateLimitService.class);
        when(limit.checkLimit(anyString(), anyString())).thenReturn(Mono.empty());
        var client = WebTestClient.bindToRouterFunction(
            new QslStationCallSignPublicEndpoint(service, limit).endpoint()).build();
        client.get().uri("/station-call-sign").exchange().expectStatus().isOk()
            .expectHeader().valueEquals("Cache-Control", "no-store")
            .expectBody().jsonPath("$.data.callSign").isEqualTo("BG1ABC")
            .jsonPath("$.data.length()").isEqualTo(1)
            .jsonPath("$.data.myName").doesNotExist()
            .jsonPath("$.data.myAddress").doesNotExist()
            .jsonPath("$.data.myTelephone").doesNotExist()
            .jsonPath("$.data.myEmail").doesNotExist();
        client.post().uri("/station-call-sign").exchange().expectStatus().is4xxClientError();
    }

    @Test
    void shouldRateLimitBeforeReadingStorage() {
        var limit = mock(QslPublicRateLimitService.class);
        when(limit.checkLimit(anyString(), anyString())).thenReturn(Mono.error(
            new QslApiException(HttpStatus.TOO_MANY_REQUESTS, "QSL-429-0001", "请求过于频繁")));
        WebTestClient.bindToRouterFunction(new QslStationCallSignPublicEndpoint(service, limit).endpoint())
            .build().get().uri("/station-call-sign").exchange()
            .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verifyNoInteractions(storage);
    }

    @Test
    void shouldUpdateAllPageExamplesWithoutChangingPrefill() {
        var pages = pages("BG1ABC");
        for (var html : pages) {
            assertThat(html).contains("BG1ABC\"").doesNotContain("例如 BI1KBU", "placeholder=\"BI1KBU\"");
        }
        for (var html : pages.subList(0, 4)) {
            assertThat(html).contains("value=\"BA1XYZ\"");
        }
        assertThat(pages.get(4)).contains("placeholder=\"BG1ABC\"").doesNotContain("value=\"BG1ABC\"");
    }

    @Test
    void shouldEscapeExampleAndPreserveUserRemarks() {
        for (var html : pages("BG1ABC\"<>&'")) {
            assertThat(html).contains("BG1ABC&quot;&lt;&gt;&amp;&#39;").doesNotContain("BG1ABC\"<>&'");
        }
        assertThat(new QslPublicReceiptPageRenderService()
            .render("BA1XYZ", "C1", "__EXAMPLE_CALL_SIGN_HTML__", false, "test", "BG1ABC"))
            .contains("__EXAMPLE_CALL_SIGN_HTML__");
    }

    private StationProfile profile(String callSign) {
        var profile = new StationProfile();
        var spec = new StationProfile.StationProfileSpec();
        spec.setMyCallSign(callSign);
        profile.setSpec(spec);
        return profile;
    }

    private List<String> pages(String example) {
        return List.of(
            new QslPublicCardPageRenderService().render("BA1XYZ", false, "test", example),
            new QslPublicReceiptPageRenderService().render("BA1XYZ", "C1", "备注", false, "test", example),
            new QslPublicOnlineExchangePageRenderService().render("BA1XYZ", "备注", false, "test", example),
            new QslPublicOfflineExchangePageRenderService().render("BA1XYZ", "C1", "A1", "备注", false, "test", example),
            new QslPublicCardRequestPageRenderService().render(example)
        );
    }
}
