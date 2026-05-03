package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.ExchangeRequest;
import com.bi1kbu.qslmanagement.extension.model.StationCard;
import com.bi1kbu.qslmanagement.extension.model.StationProfile;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslPublicApiServiceValidationTest {

    @Test
    void shouldCachePublicStationCardsAndAvoidRepeatedExtensionList() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var stationCard = new StationCard();
        stationCard.setMetadata(QslApiSupport.createMetadata("station-card-001"));
        var spec = new StationCard.StationCardSpec();
        spec.setCardVersion("2026春季版");
        spec.setImageAttachmentName("attachment-001");
        spec.setImageAttachmentDisplayName("卡片图案.png");
        spec.setImagePermalink("/upload/card.png");
        spec.setImageThumbnailUrl("/upload/card-thumbnail.png");
        spec.setImageMediaType("image/png");
        spec.setImageSize(1024);
        spec.setVersionTotal(500);
        spec.setAvailableInventory(300);
        spec.setSortOrder(1);
        stationCard.setSpec(spec);

        Mockito.when(client.listAll(Mockito.eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        Mockito.when(client.listAll(Mockito.eq(StationCard.class), any(), any())).thenReturn(Flux.just(stationCard));

        var service = new QslPublicApiService(
            client,
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var first = service.listPublicStationCards().block();
        var second = service.listPublicStationCards().block();

        assertEquals(1, first.size());
        assertEquals("2026春季版", second.get(0).cardVersion());
        verify(client, times(1)).listAll(Mockito.eq(StationCard.class), any(), any());
    }

    @Test
    void shouldRejectInvalidCallSignOnPublicQuery() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var error = assertThrows(QslApiException.class, () -> service.listPublicRecords("!@#", "").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectInvalidEmailOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "invalid-email",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankPersonalAddressOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "",
            "张三",
            "",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankBureauAddressOnExchangeSubmit() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.TRUE,
            "北京卡片局",
            "",
            "",
            "",
            "",
            "",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectDuplicatePendingOnlineExchangeSubmit() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var existing = new ExchangeRequest();
        var spec = new ExchangeRequest.ExchangeRequestSpec();
        spec.setSceneType("ONLINE_EYEBALL");
        spec.setCallSign("bi1kbu");
        existing.setSpec(spec);
        var status = new ExchangeRequest.ExchangeRequestStatus();
        status.setReviewStatus("待审核");
        existing.setStatus(status);

        Mockito.when(client.listAll(
            Mockito.eq(ExchangeRequest.class),
            Mockito.any(),
            Mockito.any()
        )).thenReturn(Flux.just(existing));

        var service = new QslPublicApiService(
            client,
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );
        var error = assertThrows(QslApiException.class, () -> service.submitExchangeRequest(command, "127.0.0.1").block());
        assertEquals("QSL-409-0001", error.getCode());
        assertEquals(409, error.getStatus().value());
    }

    @Test
    void shouldAutoApproveExchangeSubmitWhenReviewDisabled() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);
        var consoleActionService = Mockito.mock(QslConsoleActionService.class);
        var persistedRequest = new AtomicReference<ExchangeRequest>();

        var stationCard = new StationCard();
        stationCard.setMetadata(QslApiSupport.createMetadata("station-card-001"));
        var cardSpec = new StationCard.StationCardSpec();
        cardSpec.setCardVersion("2026春季版");
        cardSpec.setAvailableInventory(100);
        cardSpec.setSortOrder(1);
        stationCard.setSpec(cardSpec);

        var systemSetting = new SystemSetting();
        var settingSpec = new SystemSetting.SystemSettingSpec();
        settingSpec.setRequiresExchangeReview(Boolean.FALSE);
        systemSetting.setSpec(settingSpec);

        var stationProfile = new StationProfile();
        var profileSpec = new StationProfile.StationProfileSpec();
        profileSpec.setMyCallSign("BI1KBU");
        profileSpec.setMyAddress("北京市测试路1号");
        stationProfile.setSpec(profileSpec);

        when(client.listAll(eq(ExchangeRequest.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(StationCard.class), any(), any())).thenReturn(Flux.just(stationCard));
        when(client.create(any(ExchangeRequest.class))).thenAnswer(invocation -> {
            var created = invocation.<ExchangeRequest>getArgument(0);
            persistedRequest.set(created);
            return Mono.just(created);
        });
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(client.fetch(eq(SystemSetting.class), anyString())).thenReturn(Mono.just(systemSetting));
        when(consoleActionService.reviewExchangeRequest(anyString(), eq(true), eq("系统自动审批通过"), eq("系统自动审批"), eq("127.0.0.1")))
            .thenAnswer(invocation -> {
                var request = persistedRequest.get();
                request.getStatus().setReviewStatus("已通过");
                request.getStatus().setReviewReason("系统自动审批通过");
                return Mono.just(new QslConsoleActionService.ExchangeReviewResult(
                    request.getMetadata().getName(),
                    "BI1KBU",
                    "已通过",
                    "系统自动审批通过"
                ));
            });
        when(client.fetch(eq(ExchangeRequest.class), anyString())).thenAnswer(invocation -> Mono.just(persistedRequest.get()));
        when(client.fetch(eq(StationProfile.class), anyString())).thenReturn(Mono.just(stationProfile));

        var service = new QslPublicApiService(
            client,
            auditService,
            consoleActionService
        );

        var command = new QslPublicApiService.PublicExchangeSubmitCommand(
            "BI1KBU",
            Boolean.FALSE,
            "",
            "",
            "张三",
            "13800000000",
            "510000",
            "广东省广州市",
            "测试",
            "2026春季版"
        );

        var result = service.submitExchangeRequest(command, "127.0.0.1").block();

        assertEquals("已通过", result.reviewStatus());
        assertEquals("系统自动审批通过", persistedRequest.get().getStatus().getReviewReason());
        verify(consoleActionService).reviewExchangeRequest(
            anyString(),
            eq(true),
            eq("系统自动审批通过"),
            eq("系统自动审批"),
            eq("127.0.0.1")
        );
    }

    @Test
    void shouldRejectTooLongReceiptRemarks() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var tooLongRemarks = "x".repeat(501);
        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BI1KBU", "card-record-001", tooLongRemarks, "");
        var error = assertThrows(QslApiException.class, () -> service.confirmReceipt(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }

    @Test
    void shouldRejectBlankCardIdOnReceiptConfirm() {
        var service = new QslPublicApiService(
            Mockito.mock(ReactiveExtensionClient.class),
            Mockito.mock(QslAuditService.class),
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicReceiptConfirmCommand("BI1KBU", "", "测试", "");
        var error = assertThrows(QslApiException.class, () -> service.confirmReceipt(command, "127.0.0.1").block());
        assertEquals("QSL-400-0001", error.getCode());
        assertEquals(400, error.getStatus().value());
    }
}
