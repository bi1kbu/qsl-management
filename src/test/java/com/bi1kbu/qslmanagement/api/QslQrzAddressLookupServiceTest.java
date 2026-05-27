package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;

class QslQrzAddressLookupServiceTest {

    @Test
    void shouldExtractQrzCnMainContentAndImageFileNameTokens() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );
        var html = """
            <html>
              <head><title>BI1KBU - QRZ.CN</title></head>
              <body>
                <nav>导航 噪音</nav>
                <div class="callsign-profile">
                  <h1>BI1KBU</h1>
                  <p>姓名：测试台</p>
                  <p>地址：北京市 海淀区 测试路 1 号</p>
                  <img src="/uploads/13800000000.png" alt="电话">
                  <img title="邮编" src="/img/100000.jpg">
                </div>
              </body>
            </html>
            """;

        var feature = service.compactQrzCnHtmlFeature("BI1KBU", html);

        assertTrue(feature.contains("来源：QRZ.CN 查询页面预处理"));
        assertTrue(feature.contains("标题：BI1KBU - QRZ.CN"));
        assertTrue(feature.contains("主体文本："));
        assertTrue(feature.contains("北京市 海淀区 测试路 1 号"));
        assertTrue(feature.contains("13800000000"));
        assertTrue(feature.contains("100000"));
        assertFalse(feature.contains("导航 噪音"));
        assertFalse(feature.contains("<img"));
    }

    @Test
    void shouldUseCallSignWindowWhenQrzCnPageHasNoSemanticMainBlock() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );
        var html = """
            <html>
              <head><title>QRZ·中国 - 呼号查询</title></head>
              <body>
                <div>页面头部 <img src="../images/web/banner_qsl.gif"></div>
                <table>
                  <tr><td>呼号 <span class="title">BI1KBU</span></td></tr>
                  <tr><td>姓名：测试台</td></tr>
                  <tr><td>地址：北京市 海淀区 测试路 1 号</td></tr>
                  <tr><td><img src="/uploads/100000.png" alt="邮编"></td></tr>
                </table>
              </body>
            </html>
            """;

        var feature = service.compactQrzCnHtmlFeature("BI1KBU", html);

        assertTrue(feature.contains("BI1KBU"));
        assertTrue(feature.contains("北京市 海淀区 测试路 1 号"));
        assertTrue(feature.contains("100000"));
        assertFalse(feature.contains("banner qsl"));
    }

    @Test
    void shouldRejectQrzCnSearchPageWithoutTargetCallSign() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );
        var html = """
            <html>
              <head><title>QRZ·中国 - 呼号查询</title></head>
              <body>
                <form action="../call/" method="post" name="f">
                  <input autocomplete="off" name="q" type="text" />
                  <input name="btn_callsign" type="image" src="../images/web/btn_go.gif" />
                </form>
              </body>
            </html>
            """;

        assertFalse(service.isUsefulQrzCnHtml(html, "BA1AA"));
    }

    @Test
    void shouldRejectQrzCnNoDataPageEvenWhenTargetCallSignIsPresent() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );
        var html = """
            <html>
              <head><title>QRZ·中国 - 呼号查询</title></head>
              <body>
                <p>BG2GWS 没有在我们的数据库中。</p>
                <p>如果你知道,请点这里添加该呼号信息。</p>
              </body>
            </html>
            """;

        assertFalse(service.isUsefulQrzCnHtml(html, "BG2GWS"));
    }

    @Test
    void shouldAcceptQrzCnResultPageWithTargetCallSign() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );
        var html = """
            <html>
              <head><title>BA1AA - QRZ.CN</title></head>
              <body>
                <table>
                  <tr><td>呼号：BA1AA</td></tr>
                  <tr><td>地址：北京市 朝阳区 示例地址</td></tr>
                </table>
              </body>
            </html>
            """;

        assertTrue(service.isUsefulQrzCnHtml(html, "BA1AA"));
    }

    @Test
    void shouldMergeQrzCnCookieHeadersByName() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );

        var merged = service.mergeCookieHeaders(
            "CFID=old; CFTOKEN=old-token",
            "CFID=new; path=/; CFTOKEN=new-token; HttpOnly"
        );

        assertTrue(merged.contains("CFID=new"));
        assertTrue(merged.contains("CFTOKEN=new-token"));
        assertFalse(merged.contains("old-token"));
        assertFalse(merged.contains("path=/"));
    }

    @Test
    void shouldDetectQrzCnLoggedInPage() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );

        assertTrue(service.isQrzCnLoggedInPage("<html><body><a href=\"login.php?action=quit\">退出</a></body></html>"));
        assertFalse(service.isQrzCnLoggedInPage("<html><body><form name=\"form_login\"></form></body></html>"));
    }

    @Test
    void shouldRejectNonOfficialQrzUrls() {
        var service = new QslQrzAddressLookupService(
            mock(ReactiveExtensionClient.class),
            mock(QslAuditService.class),
            mock(QslAiService.class)
        );

        assertThrows(QslApiException.class, () -> service.normalizeQrzComBaseUrl("http://127.0.0.1:8080/"));
        assertThrows(QslApiException.class,
            () -> service.normalizeQrzCnLookupUrlTemplate("http://127.0.0.1/call/{callSign}"));
        assertThrows(QslApiException.class,
            () -> service.normalizeQrzCnLookupUrlTemplate("https://www.qrz.cn/call/BI1KBU"));
    }

    @Test
    void shouldNotPersistQrzCredentialWhenValidationFails() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslQrzAddressLookupService(client, auditService, mock(QslAiService.class));
        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        systemSetting.setSpec(new SystemSetting.SystemSettingSpec());

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));

        assertThrows(QslApiException.class, () -> service.testAndSaveCredential(
            new QslQrzAddressLookupService.QrzCredentialCommand(
                "QRZ_COM",
                Boolean.TRUE,
                "BI1KBU",
                "secret",
                "",
                "qsl-qrz-com-credential",
                "http://127.0.0.1:8080/xml/current/",
                "",
                30,
                Boolean.TRUE,
                "BI1KBU"
            ),
            "admin",
            "127.0.0.1"
        ).block());

        verify(client, never()).update(any(SystemSetting.class));
    }
}
