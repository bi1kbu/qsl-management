package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.BureauEntry;
import com.bi1kbu.qslmanagement.extension.model.CardRecord;
import com.bi1kbu.qslmanagement.extension.model.QslCardRequest;
import com.bi1kbu.qslmanagement.extension.model.QslCardRequestQsoReservation;
import com.bi1kbu.qslmanagement.extension.model.QsoRecord;
import com.bi1kbu.qslmanagement.extension.model.StationCard;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ReactiveExtensionClient;

class QslCardRequestServiceTest {

    @Test
    void shouldExposeExistingCardAndPendingReservationReasons() {
        var client = mock(ReactiveExtensionClient.class);
        var qso1 = qso("QSO0001", "BI1KBU");
        var qso2 = qso("QSO0002", "BI1KBU");
        var qso3 = qso("QSO0003", "BI1KBU");
        var card = card("C1001", "QSO0001");
        var reservation = reservation("pending", "QSO0002");

        when(client.listAll(eq(QsoRecord.class), any(), any())).thenReturn(Flux.just(qso1, qso2, qso3));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.just(card));
        when(client.listAll(eq(QslCardRequestQsoReservation.class), any(), any()))
            .thenReturn(Flux.just(reservation));

        var result = service(client).listEligibleQso("bi1kbu").block();

        assertEquals(3, result.total());
        assertFalse(result.items().get(0).selectable());
        assertEquals("已有卡片", result.items().get(0).unselectableReason());
        assertFalse(result.items().get(1).selectable());
        assertEquals("待审核", result.items().get(1).unselectableReason());
        assertTrue(result.items().get(2).selectable());
    }

    @Test
    void shouldSubmitPersonalAddressWithOptionalNameAndTelephoneAndPersistReservation() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var actionService = mock(QslConsoleActionService.class);
        var notificationService = mock(QslNotificationMailService.class);
        var created = new ArrayList<Extension>();
        var qso = qso("QSO0001", "BI1KBU");
        var stationCard = stationCard("2026版", 20);
        var historicalAddress = personalAddress("BI1KBU-1", "old@example.com");

        when(client.fetch(eq(QsoRecord.class), eq("QSO0001"))).thenReturn(Mono.just(qso));
        when(client.fetch(eq(QslCardRequestQsoReservation.class), anyString())).thenReturn(Mono.empty());
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(QslCardRequestQsoReservation.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(StationCard.class), any(), any())).thenReturn(Flux.just(stationCard));
        when(client.listAll(eq(AddressBookEntry.class), any(), any())).thenReturn(Flux.just(historicalAddress));
        when(client.listAll(eq(QslCardRequest.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any())).thenAnswer(invocation -> {
            var extension = invocation.getArgument(0, Extension.class);
            created.add(extension);
            return Mono.just(extension);
        });
        when(auditService.appendAuditLog(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.empty());

        var service = new QslCardRequestService(client, auditService, actionService, notificationService);
        var result = service.submit(new QslCardRequestService.SubmitCommand(
            "BI1KBU",
            List.of(new QslCardRequestService.RequestedQsoItem("QSO0001", "2026版")),
            "PERSONAL",
            "",
            "",
            "100000",
            "北京市测试地址",
            "",
            "user@example.com",
            "请寄送实体卡"
        ), "127.0.0.1").block();

        assertEquals("QCR0001", result.requestName());
        assertEquals("待处理", result.reviewStatus());
        var createdAddress = created.stream()
            .filter(AddressBookEntry.class::isInstance)
            .map(AddressBookEntry.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals("BI1KBU-2", createdAddress.getMetadata().getName());
        assertEquals("", createdAddress.getSpec().getName());
        assertEquals("", createdAddress.getSpec().getTelephone());
        assertEquals("user@example.com", createdAddress.getSpec().getEmail());
        assertEquals("old@example.com", historicalAddress.getSpec().getEmail());
        assertTrue(created.stream().anyMatch(QslCardRequestQsoReservation.class::isInstance));
        assertTrue(created.stream().anyMatch(QslCardRequest.class::isInstance));
    }

    @Test
    void shouldSubmitBureauRequestWithoutReadingOrCreatingPersonalAddress() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var created = new ArrayList<Extension>();
        var qso = qso("QSO0001", "BI1KBU");
        var bureau = bureau("BURO-1");

        when(client.fetch(eq(QsoRecord.class), eq("QSO0001"))).thenReturn(Mono.just(qso));
        when(client.fetch(eq(QslCardRequestQsoReservation.class), anyString())).thenReturn(Mono.empty());
        when(client.fetch(eq(BureauEntry.class), eq("BURO-1"))).thenReturn(Mono.just(bureau));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(StationCard.class), any(), any())).thenReturn(Flux.just(stationCard("2026版", 20)));
        when(client.listAll(eq(QslCardRequest.class), any(), any())).thenReturn(Flux.empty());
        when(client.create(any())).thenAnswer(invocation -> {
            var extension = invocation.getArgument(0, Extension.class);
            created.add(extension);
            return Mono.just(extension);
        });
        when(auditService.appendAuditLog(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.empty());

        var service = new QslCardRequestService(
            client,
            auditService,
            mock(QslConsoleActionService.class),
            mock(QslNotificationMailService.class)
        );
        var result = service.submit(new QslCardRequestService.SubmitCommand(
            "BI1KBU",
            List.of(new QslCardRequestService.RequestedQsoItem("QSO0001", "2026版")),
            "BUREAU", "", "", "", "", "BURO-1", "user@example.com", "卡片局寄送"
        ), "127.0.0.1").block();

        assertEquals("QCR0001", result.requestName());
        var request = created.stream()
            .filter(QslCardRequest.class::isInstance)
            .map(QslCardRequest.class::cast)
            .findFirst()
            .orElseThrow();
        assertEquals("BUREAU", request.getSpec().getAddressType());
        assertEquals("BURO-1", request.getSpec().getAddressEntryName());
        assertFalse(created.stream().anyMatch(AddressBookEntry.class::isInstance));
    }

    @Test
    void shouldReleaseReservationsWhenRequestIsRejected() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var notificationService = mock(QslNotificationMailService.class);
        var request = request("QCR0001", "BI1KBU", "QSO0001", "2026版");
        var reservation = reservation("QCR0001", "QSO0001");

        when(client.fetch(eq(QslCardRequest.class), eq("QCR0001"))).thenReturn(Mono.just(request));
        when(client.update(any(QslCardRequest.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.listAll(eq(QslCardRequestQsoReservation.class), any(), any()))
            .thenReturn(Flux.just(reservation));
        when(client.delete(reservation)).thenReturn(Mono.empty());
        when(auditService.appendAuditLog(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.empty());
        when(notificationService.sendQslCardRequestReviewMail(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.just(new QslNotificationMailService.QslCardRequestReviewMailSendResult(
                "QCR0001", "SENT", "发送成功。", "user@example.com", "2026-07-16 12:00:00"
            )));

        var service = new QslCardRequestService(
            client,
            auditService,
            mock(QslConsoleActionService.class),
            notificationService
        );
        var result = service.reject("QCR0001", "资料不完整", "admin", "127.0.0.1").block();

        assertEquals("拒绝", result.reviewStatus());
        verify(client).delete(reservation);
    }

    @Test
    void shouldReconcileOnlyStaleReservations() {
        var client = mock(ReactiveExtensionClient.class);
        var orphan = reservation("QCR0001", "QSO0001");
        var rejected = reservation("QCR0002", "QSO0002");
        var completed = reservation("QCR0003", "QSO0003");
        var pending = reservation("QCR0004", "QSO0004");
        var rejectedRequest = request("QCR0002", "BI1KBU", "QSO0002", "2026版");
        rejectedRequest.getStatus().setReviewStatus("拒绝");
        var approvedRequest = request("QCR0003", "BI1KBU", "QSO0003", "2026版");
        approvedRequest.getStatus().setReviewStatus("通过");
        var pendingRequest = request("QCR0004", "BI1KBU", "QSO0004", "2026版");

        when(client.listAll(eq(QslCardRequestQsoReservation.class), any(), any()))
            .thenReturn(Flux.just(orphan, rejected, completed, pending));
        when(client.listAll(eq(QslCardRequest.class), any(), any()))
            .thenReturn(Flux.just(rejectedRequest, approvedRequest, pendingRequest));
        when(client.listAll(eq(CardRecord.class), any(), any()))
            .thenReturn(Flux.just(card("C1003", "QSO0003")));
        when(client.delete(any(QslCardRequestQsoReservation.class))).thenReturn(Mono.empty());

        var result = service(client).reconcileReservations().block();

        assertEquals(4, result.scanned());
        assertEquals(3, result.released());
        assertEquals(1, result.kept());
        verify(client, times(3)).delete(any(QslCardRequestQsoReservation.class));
        verify(client, never()).delete(pending);
    }

    @Test
    void shouldRecheckRemainingInventoryBeforeRetry() {
        var client = mock(ReactiveExtensionClient.class);
        var actionService = mock(QslConsoleActionService.class);
        var request = request("QCR0001", "BI1KBU", "QSO0001", "2026版");
        request.getStatus().setReviewStatus("通过");
        request.getStatus().setCardCreationStatus("部分失败");
        var failed = new QslCardRequest.CreatedCardItem();
        failed.setQsoRecordName("QSO0001");
        failed.setCardVersion("2026版");
        failed.setCreationStatus("失败");
        request.getStatus().setCreatedCards(List.of(failed));

        when(client.fetch(eq(QslCardRequest.class), eq("QCR0001"))).thenReturn(Mono.just(request));
        when(client.fetch(eq(QsoRecord.class), eq("QSO0001"))).thenReturn(Mono.just(qso("QSO0001", "BI1KBU")));
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(StationCard.class), any(), any()))
            .thenReturn(Flux.just(stationCard("2026版", 0)));

        var service = new QslCardRequestService(
            client,
            mock(QslAuditService.class),
            actionService,
            mock(QslNotificationMailService.class)
        );
        var error = assertThrows(QslApiException.class,
            () -> service.retryCardCreation("QCR0001", "admin", "127.0.0.1").block());

        assertEquals("QSL-422-QCR-0001", error.getCode());
        verify(actionService, never()).createCardForQslCardRequest(
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString()
        );
    }

    @Test
    void shouldApproveAndCreateOneCardPerQsoImmediately() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var actionService = mock(QslConsoleActionService.class);
        var notificationService = mock(QslNotificationMailService.class);
        var request = request("QCR0001", "BI1KBU", "QSO0001", "2026版");
        var qso = qso("QSO0001", "BI1KBU");
        var stationCard = stationCard("2026版", 20);
        var card = card("C1001", "QSO0001");

        when(client.fetch(eq(QslCardRequest.class), eq("QCR0001"))).thenReturn(Mono.just(request));
        when(client.fetch(eq(QsoRecord.class), eq("QSO0001"))).thenReturn(Mono.just(qso));
        when(client.fetch(eq(QslCardRequestQsoReservation.class), anyString())).thenReturn(Mono.empty());
        when(client.listAll(eq(CardRecord.class), any(), any())).thenReturn(Flux.empty());
        when(client.listAll(eq(StationCard.class), any(), any())).thenReturn(Flux.just(stationCard));
        when(client.listAll(eq(QslCardRequestQsoReservation.class), any(), any())).thenReturn(Flux.empty());
        when(client.update(any(QslCardRequest.class)))
            .thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(actionService.createCardForQslCardRequest(
            eq("QCR0001"), eq("BI1KBU"), eq("QSO0001"), eq("2026版"),
            eq("BI1KBU-1"), eq("user@example.com"), anyString()
        )).thenReturn(Mono.just(card));
        when(notificationService.autoSendIfEnabled(anyString(), any(), anyString(), anyString()))
            .thenReturn(Mono.empty());
        when(notificationService.sendQslCardRequestReviewMail(anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.just(new QslNotificationMailService.QslCardRequestReviewMailSendResult(
                "QCR0001", "SENT", "发送成功。", "user@example.com", "2026-07-16 12:00:00"
            )));
        when(auditService.appendAuditLog(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(Mono.empty());

        var service = new QslCardRequestService(client, auditService, actionService, notificationService);
        var result = service.approve("QCR0001", "资料完整", "admin", "127.0.0.1").block();

        assertEquals("通过", result.reviewStatus());
        assertEquals("全部成功", result.cardCreationStatus());
        assertEquals(1, result.createdCards().size());
        assertEquals("C1001", result.createdCards().get(0).getCardRecordName());
    }

    @Test
    void shouldRejectInvalidNotificationEmail() {
        var service = service(mock(ReactiveExtensionClient.class));
        var error = assertThrows(QslApiException.class, () -> service.submit(
            new QslCardRequestService.SubmitCommand(
                "BI1KBU",
                List.of(new QslCardRequestService.RequestedQsoItem("QSO0001", "2026版")),
                "PERSONAL", "测试用户", "13800000000", "100000", "北京市测试地址",
                "", "invalid", ""
            ), "127.0.0.1"
        ));
        assertEquals("QSL-400-QCR-0001", error.getCode());
    }

    @Test
    void shouldRejectMissingPersonalPostalCodeOrAddress() {
        var service = service(mock(ReactiveExtensionClient.class));
        var missingPostalCode = assertThrows(QslApiException.class, () -> service.submit(
            new QslCardRequestService.SubmitCommand(
                "BI1KBU",
                List.of(new QslCardRequestService.RequestedQsoItem("QSO0001", "2026版")),
                "PERSONAL", "", "", "", "北京市测试地址",
                "", "user@example.com", ""
            ), "127.0.0.1"
        ));
        var missingAddress = assertThrows(QslApiException.class, () -> service.submit(
            new QslCardRequestService.SubmitCommand(
                "BI1KBU",
                List.of(new QslCardRequestService.RequestedQsoItem("QSO0001", "2026版")),
                "PERSONAL", "", "", "100000", "",
                "", "user@example.com", ""
            ), "127.0.0.1"
        ));

        assertEquals("QSL-400-QCR-0001", missingPostalCode.getCode());
        assertEquals("QSL-400-QCR-0001", missingAddress.getCode());
    }

    private QslCardRequestService service(ReactiveExtensionClient client) {
        return new QslCardRequestService(
            client,
            mock(QslAuditService.class),
            mock(QslConsoleActionService.class),
            mock(QslNotificationMailService.class)
        );
    }

    private QsoRecord qso(String name, String callSign) {
        var qso = new QsoRecord();
        qso.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new QsoRecord.QsoRecordSpec();
        spec.setSceneType("QSO");
        spec.setCallSign(callSign);
        spec.setDate("2026-07-16");
        spec.setTime("120000");
        spec.setFreq("14.270");
        spec.setQth("OM89");
        qso.setSpec(spec);
        return qso;
    }

    private CardRecord card(String name, String qsoRecordName) {
        var card = new CardRecord();
        card.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new CardRecord.CardRecordSpec();
        spec.setQsoRecordName(qsoRecordName);
        spec.setCardVersion("2026版");
        spec.setBusinessRemarks("实体QSL卡申请审核通过自动创建。申请编号：QCR0001");
        card.setSpec(spec);
        return card;
    }

    private StationCard stationCard(String version, int inventory) {
        var card = new StationCard();
        card.setMetadata(QslApiSupport.createMetadata("station-card-1"));
        var spec = new StationCard.StationCardSpec();
        spec.setCardVersion(version);
        spec.setAvailableInventory(inventory);
        card.setSpec(spec);
        var status = new StationCard.StationCardStatus();
        status.setActive(Boolean.TRUE);
        card.setStatus(status);
        return card;
    }

    private QslCardRequestQsoReservation reservation(String requestName, String qsoRecordName) {
        var reservation = new QslCardRequestQsoReservation();
        reservation.setMetadata(QslApiSupport.createMetadata("reservation-" + qsoRecordName.toLowerCase()));
        var spec = new QslCardRequestQsoReservation.QslCardRequestQsoReservationSpec();
        spec.setRequestName(requestName);
        spec.setQsoRecordName(qsoRecordName);
        reservation.setSpec(spec);
        return reservation;
    }

    private AddressBookEntry personalAddress(String name, String email) {
        var address = new AddressBookEntry();
        address.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new AddressBookEntry.AddressBookSpec();
        spec.setCallSign("BI1KBU");
        spec.setName("测试用户");
        spec.setTelephone("13800000000");
        spec.setPostalCode("100000");
        spec.setAddress("北京市测试地址");
        spec.setEmail(email);
        address.setSpec(spec);
        return address;
    }

    private BureauEntry bureau(String name) {
        var bureau = new BureauEntry();
        bureau.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new BureauEntry.BureauSpec();
        spec.setBureauName("测试卡片局");
        spec.setPostalCode("100000");
        spec.setAddress("北京市测试卡片局地址");
        bureau.setSpec(spec);
        return bureau;
    }

    private QslCardRequest request(String name, String callSign, String qsoName, String cardVersion) {
        var request = new QslCardRequest();
        request.setMetadata(QslApiSupport.createMetadata(name));
        var spec = new QslCardRequest.QslCardRequestSpec();
        spec.setCallSign(callSign);
        var item = new QslCardRequest.QsoItem();
        item.setQsoRecordName(qsoName);
        item.setCardVersion(cardVersion);
        spec.setQsoItems(List.of(item));
        spec.setAddressType("PERSONAL");
        spec.setAddressEntryName("BI1KBU-1");
        spec.setNotificationEmail("user@example.com");
        spec.setRemarks("测试申请");
        request.setSpec(spec);
        var status = new QslCardRequest.QslCardRequestStatus();
        status.setReviewStatus("待处理");
        status.setCardCreationStatus("未创建");
        status.setCreatedCards(new ArrayList<>());
        request.setStatus(status);
        return request;
    }
}
