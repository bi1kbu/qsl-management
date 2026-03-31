package run.halo.qsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import run.halo.app.plugin.PluginContext;

@ExtendWith(MockitoExtension.class)
class QslManagementPluginTest {

    @Mock
    PluginContext context;

    @InjectMocks
    QslManagementPlugin plugin;

    @Test
    void pluginLifecycle() {
        plugin.start();
        plugin.stop();
    }

    @Test
    void reissueCountShouldIncreaseOnlyWhenSendConfirmed() {
        var service = new QslDataService();
        var card = service.create("card", Map.of("peerCallsign", "BH1ABC", "cardType", "EYEBALL"), "tester");
        var id = Long.parseLong(String.valueOf(card.get("id")));

        service.reissuePrepare(Map.of("cardId", id), "tester");
        var prepared = service.get("card", id);
        assertEquals(0, ((Number) prepared.get("reissueCount")).intValue());

        service.sendConfirm(Map.of("cardIds", java.util.List.of(id), "isReissue", true), "tester");
        var sent = service.get("card", id);
        assertEquals(1, ((Number) sent.get("reissueCount")).intValue());
        assertTrue("SENT".equals(sent.get("sentStatus")));
    }

    @Test
    void csvExportShouldUseUtf8Bom() {
        var service = new QslDataService();
        service.create("card", Map.of("peerCallsign", "BH1ABC", "cardType", "EYEBALL"), "tester");
        var csv = service.exportCardsCsv(java.util.List.of());
        assertTrue(csv.length > 3);
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
    }

    @Test
    void auditFilterShouldSupportOperatorAndType() {
        var service = new QslDataService();
        service.create("qso", Map.of("peerCallsign", "BH1XYZ"), "alice");
        service.create("card", Map.of("peerCallsign", "BH1XYZ", "cardType", "EYEBALL"), "bob");
        var filtered = service.filterAudit(Map.of("operatorId", "alice", "objectType", "qso"));
        assertNotNull(filtered);
        assertTrue(filtered.size() >= 1);
        assertTrue(filtered.stream().allMatch(i -> "alice".equals(i.get("operatorId"))));
    }

    @Test
    void addressBookShouldDedupeByCallsignAndAddress() {
        var service = new QslDataService();
        service.create("address", Map.of("callsign", "BH1ABC", "address", "Beijing"), "tester");
        assertThrows(IllegalArgumentException.class,
            () -> service.create("address", Map.of("callsign", "BH1ABC", "address", "Beijing"), "tester"));
    }

    @Test
    void approveNormalRequestShouldGenerateEyeballCard() {
        var service = new QslDataService();
        var req = service.create("request", Map.of("requestType", "NORMAL", "bindCallsign", "BH1XYZ"), "ham");
        var approved = service.approveRequest(Long.parseLong(String.valueOf(req.get("id"))), "admin");
        assertEquals("APPROVED", approved.get("status"));
        assertNotNull(approved.get("generatedCardId"));
    }

    @Test
    void approveReissueShouldOnlyPrepareAndNotCreateCard() {
        var service = new QslDataService();
        var card = service.create("card", Map.of("peerCallsign", "BH1ABC", "cardType", "EYEBALL"), "tester");
        var cardId = Long.parseLong(String.valueOf(card.get("id")));
        service.sendConfirm(Map.of("cardIds", java.util.List.of(cardId), "isReissue", false), "tester");
        var req = service.create("request",
            Map.of("requestType", "REISSUE", "bindCallsign", "BH1ABC", "qslCardRecordId", cardId), "ham");
        var approved = service.approveRequest(Long.parseLong(String.valueOf(req.get("id"))), "admin");
        assertEquals("APPROVED", approved.get("status"));
        var updatedCard = service.get("card", cardId);
        assertEquals("PENDING_PRINT", updatedCard.get("productionStatus"));
        assertEquals("NOT_SENT", updatedCard.get("sentStatus"));
    }

    @Test
    void rateLimitShouldRespectPerMinuteLimit() {
        var limiter = new RateLimitService();
        assertTrue(limiter.allow("IP:127.0.0.1", 1));
        assertTrue(!limiter.allow("IP:127.0.0.1", 1));
    }

    @Test
    void qsoCardShouldRequireUniqueQsoRecordBinding() {
        var service = new QslDataService();
        var qso = service.create("qso", Map.of(
            "peerCallsign", "BH1AAA", "qsoDate", "2026-03-31", "qsoTime", "10:00:00",
            "frequency", "14.070", "mode", "FT8"), "tester");
        var qsoId = Long.parseLong(String.valueOf(qso.get("id")));
        service.create("card", Map.of("peerCallsign", "BH1AAA", "cardType", "QSO", "qsoRecordId", qsoId), "tester");
        assertThrows(IllegalArgumentException.class,
            () -> service.create("card", Map.of("peerCallsign", "BH1AAA", "cardType", "LISTEN", "qsoRecordId", qsoId), "tester"));
    }

    @Test
    void dashboardExportShouldContainBom() {
        var service = new QslDataService();
        service.create("card", Map.of("peerCallsign", "BH1DASH", "cardType", "EYEBALL"), "tester");
        var csv = service.exportDashboardCsv(Map.of());
        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
    }
}
