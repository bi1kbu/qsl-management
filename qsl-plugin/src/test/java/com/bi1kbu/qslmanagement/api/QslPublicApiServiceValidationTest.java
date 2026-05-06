package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @Test
    void shouldMarkCardSentWhenOfflineExchangeConfirmed() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);

        var cardRecord = new CardRecord();
        cardRecord.setMetadata(QslApiSupport.createMetadata("C1001"));
        var spec = new CardRecord.CardRecordSpec();
        spec.setSceneType("EYEBALL");
        spec.setCallSign("");
        spec.setOfflineActivityName("ACT001");
        spec.setCardSent(Boolean.FALSE);
        spec.setSentAt("");
        spec.setReceiptConfirmed(Boolean.FALSE);
        spec.setPublicReceiptRemarks("");
        cardRecord.setSpec(spec);

        var stationProfile = new StationProfile();
        var profileSpec = new StationProfile.StationProfileSpec();
        profileSpec.setMyAddress("北京市测试路1号");
        profileSpec.setMyEmail("bi1kbu@example.test");
        stationProfile.setSpec(profileSpec);

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(cardRecord));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(client.fetch(eq(StationProfile.class), anyString())).thenReturn(Mono.just(stationProfile));

        var service = new QslPublicApiService(
            client,
            auditService,
            Mockito.mock(QslConsoleActionService.class)
        );

        var command = new QslPublicApiService.PublicOfflineExchangeConfirmCommand(
            "BI1KBU",
            "C1001",
            "ACT001",
            "现场确认"
        );

        var result = service.confirmOfflineExchange(command, "127.0.0.1").block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals("BI1KBU", cardRecord.getSpec().getCallSign());
        assertEquals(Boolean.TRUE, cardRecord.getSpec().getCardSent());
        assertTrue(cardRecord.getSpec().getSentAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"));
        assertEquals(Boolean.TRUE, cardRecord.getSpec().getReceiptConfirmed());
        assertEquals("现场确认", cardRecord.getSpec().getPublicReceiptRemarks());
        verify(client).update(cardRecord);
    }

    @Test
    void shouldMoveOfflineTemporaryReceivedCodeToActivatedCardWhenActivityMatches() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);

        var activatedCard = new CardRecord();
        activatedCard.setMetadata(QslApiSupport.createMetadata("C1001"));
        var activatedSpec = new CardRecord.CardRecordSpec();
        activatedSpec.setSceneType("EYEBALL");
        activatedSpec.setCallSign("");
        activatedSpec.setOfflineActivityName("ACT001");
        activatedSpec.setCardSent(Boolean.FALSE);
        activatedSpec.setSentAt("");
        activatedSpec.setReceiptConfirmed(Boolean.FALSE);
        activatedSpec.setPublicReceiptRemarks("");
        activatedSpec.setReceivedRecordCodes("");
        activatedSpec.setCardReceived(Boolean.FALSE);
        activatedSpec.setReceivedAt("");
        activatedCard.setSpec(activatedSpec);

        var temporaryCard = new CardRecord();
        temporaryCard.setMetadata(QslApiSupport.createMetadata("C2001"));
        var temporarySpec = new CardRecord.CardRecordSpec();
        temporarySpec.setSceneType("EYEBALL");
        temporarySpec.setCallSign("BI1KBU");
        temporarySpec.setOfflineActivityName("ACT001");
        temporarySpec.setCardReceived(Boolean.TRUE);
        temporarySpec.setReceivedAt("2026-05-06 10:00:00");
        temporarySpec.setReceivedRecordCodes("R0001-20260506");
        temporarySpec.setReceivedMailStatus("SENT");
        temporarySpec.setBusinessRemarks("自动创建EYEBALL卡片");
        temporaryCard.setSpec(temporarySpec);

        var stationProfile = new StationProfile();
        var profileSpec = new StationProfile.StationProfileSpec();
        profileSpec.setMyAddress("北京市测试路1号");
        profileSpec.setMyEmail("bi1kbu@example.test");
        stationProfile.setSpec(profileSpec);

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(activatedCard));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(temporaryCard));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.delete(any(CardRecord.class))).thenReturn(Mono.empty());
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(client.fetch(eq(StationProfile.class), anyString())).thenReturn(Mono.just(stationProfile));

        var service = new QslPublicApiService(
            client,
            auditService,
            Mockito.mock(QslConsoleActionService.class)
        );

        var result = service.confirmOfflineExchange(
            new QslPublicApiService.PublicOfflineExchangeConfirmCommand(
                "BI1KBU",
                "C1001",
                "ACT001",
                "现场确认"
            ),
            "127.0.0.1"
        ).block();

        assertEquals("C1001", result.cardRecordName());
        assertEquals("BI1KBU", activatedCard.getSpec().getCallSign());
        assertEquals(Boolean.TRUE, activatedCard.getSpec().getCardReceived());
        assertEquals("R0001-20260506", activatedCard.getSpec().getReceivedRecordCodes());
        assertEquals("2026-05-06 10:00:00", activatedCard.getSpec().getReceivedAt());
        verify(client, times(1)).update(activatedCard);
        verify(client, times(1)).delete(temporaryCard);
    }

    @Test
    void shouldNotMoveOfflineTemporaryReceivedCodeWhenActivityDiffers() {
        var client = Mockito.mock(ReactiveExtensionClient.class);
        var auditService = Mockito.mock(QslAuditService.class);

        var activatedCard = new CardRecord();
        activatedCard.setMetadata(QslApiSupport.createMetadata("C1001"));
        var activatedSpec = new CardRecord.CardRecordSpec();
        activatedSpec.setSceneType("EYEBALL");
        activatedSpec.setCallSign("");
        activatedSpec.setOfflineActivityName("ACT001");
        activatedSpec.setCardSent(Boolean.FALSE);
        activatedSpec.setReceiptConfirmed(Boolean.FALSE);
        activatedSpec.setPublicReceiptRemarks("");
        activatedSpec.setReceivedRecordCodes("");
        activatedSpec.setCardReceived(Boolean.FALSE);
        activatedCard.setSpec(activatedSpec);

        var temporaryCard = new CardRecord();
        temporaryCard.setMetadata(QslApiSupport.createMetadata("C2001"));
        var temporarySpec = new CardRecord.CardRecordSpec();
        temporarySpec.setSceneType("EYEBALL");
        temporarySpec.setCallSign("BI1KBU");
        temporarySpec.setOfflineActivityName("ACT002");
        temporarySpec.setCardReceived(Boolean.TRUE);
        temporarySpec.setReceivedAt("2026-05-06 10:00:00");
        temporarySpec.setReceivedRecordCodes("R0001-20260506");
        temporaryCard.setSpec(temporarySpec);

        var stationProfile = new StationProfile();
        stationProfile.setSpec(new StationProfile.StationProfileSpec());

        when(client.fetch(eq(CardRecord.class), eq("C1001"))).thenReturn(Mono.just(activatedCard));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(temporaryCard));
        when(client.update(any(CardRecord.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());
        when(client.fetch(eq(StationProfile.class), anyString())).thenReturn(Mono.just(stationProfile));

        var service = new QslPublicApiService(
            client,
            auditService,
            Mockito.mock(QslConsoleActionService.class)
        );

        service.confirmOfflineExchange(
            new QslPublicApiService.PublicOfflineExchangeConfirmCommand(
                "BI1KBU",
                "C1001",
                "ACT001",
                "现场确认"
            ),
            "127.0.0.1"
        ).block();

        assertEquals("", activatedCard.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.FALSE, activatedCard.getSpec().getCardReceived());
        assertEquals("R0001-20260506", temporaryCard.getSpec().getReceivedRecordCodes());
        assertEquals(Boolean.TRUE, temporaryCard.getSpec().getCardReceived());
        verify(client, times(1)).update(any(CardRecord.class));
        verify(client, times(0)).delete(any(CardRecord.class));
    }
}
