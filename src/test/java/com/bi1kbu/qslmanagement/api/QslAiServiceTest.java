package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

class QslAiServiceTest {

    @Test
    void shouldSaveApiKeyToSecretStringData() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslAiService(client, auditService);
        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        systemSetting.setSpec(new SystemSetting.SystemSettingSpec());
        var capturedSecret = new AtomicReference<Secret>();

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.fetch(eq(Secret.class), eq("qsl-ai-openai-api-key")))
            .thenReturn(Mono.empty(), Mono.empty());
        when(client.create(any(Secret.class))).thenAnswer(invocation -> {
            capturedSecret.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.saveConfiguration(
            new QslAiService.AiConfigurationCommand(
                Boolean.TRUE,
                "openai-compatible",
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                "qsl-ai-openai-api-key",
                "sk-test",
                0.2,
                30,
                1,
                30000,
                Boolean.TRUE,
                Boolean.TRUE,
                "",
                "",
                "",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals(Secret.SECRET_TYPE_OPAQUE, capturedSecret.get().getType());
        assertEquals("sk-test", capturedSecret.get().getStringData().get("apiKey"));
    }

    @Test
    void shouldUpdateExistingApiKeySecretWithoutCreatingDuplicate() {
        var client = mock(ReactiveExtensionClient.class);
        var auditService = mock(QslAuditService.class);
        var service = new QslAiService(client, auditService);
        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata("qsl-system-setting-default"));
        systemSetting.setSpec(new SystemSetting.SystemSettingSpec());
        var existingSecret = new Secret();
        existingSecret.setMetadata(QslApiSupport.createMetadata("qsl-ai-openai-api-key"));
        var capturedSecret = new AtomicReference<Secret>();

        when(client.fetch(eq(SystemSetting.class), eq("qsl-system-setting-default")))
            .thenReturn(Mono.just(systemSetting));
        when(client.update(any(SystemSetting.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));
        when(client.fetch(eq(Secret.class), eq("qsl-ai-openai-api-key")))
            .thenReturn(Mono.just(existingSecret), Mono.just(existingSecret));
        when(client.update(any(Secret.class))).thenAnswer(invocation -> {
            capturedSecret.set(invocation.getArgument(0));
            return Mono.just(invocation.getArgument(0));
        });
        when(auditService.appendAuditLog(any(), any(), any(), any(), any(), any())).thenReturn(Mono.empty());

        service.saveConfiguration(
            new QslAiService.AiConfigurationCommand(
                Boolean.TRUE,
                "openai-compatible",
                "https://api.openai.com/v1",
                "gpt-4.1-mini",
                "qsl-ai-openai-api-key",
                "sk-updated",
                0.2,
                30,
                1,
                30000,
                Boolean.TRUE,
                Boolean.TRUE,
                "",
                "",
                "",
                ""
            ),
            "admin",
            "127.0.0.1"
        ).block();

        assertEquals(Secret.SECRET_TYPE_OPAQUE, capturedSecret.get().getType());
        assertEquals("sk-updated", capturedSecret.get().getStringData().get("apiKey"));
        verify(client, never()).create(any(Secret.class));
    }

    @Test
    void shouldDecodeBase64SecretData() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod("decodeSecretData", byte[].class);
        method.setAccessible(true);
        var encoded = Base64.getEncoder().encodeToString("sk-decoded".getBytes(StandardCharsets.UTF_8));

        var decoded = (String) method.invoke(service, encoded.getBytes(StandardCharsets.UTF_8));

        assertEquals("sk-decoded", decoded);
    }

    @Test
    void shouldExtractJsonObjectFromMarkdownAiContent() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod("parseOpenAiContent", String.class);
        method.setAccessible(true);
        var response = """
            {
              "choices": [
                {
                  "message": {
                    "content": "```json\\n{\\\"ok\\\":true,\\\"message\\\":\\\"连接正常\\\"}\\n```"
                  }
                }
              ]
            }
            """;

        var json = (com.fasterxml.jackson.databind.JsonNode) method.invoke(service, response);

        assertEquals(true, json.path("ok").asBoolean(false));
        assertEquals("连接正常", json.path("message").asText());
    }

    @Test
    void shouldNormalizeCommonOnlineImportAliasFields() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod(
            "parseOnlineImportRows",
            com.fasterxml.jackson.databind.JsonNode.class,
            String.class
        );
        method.setAccessible(true);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree("""
            {
              "rows": [
                {
                  "callsign": "bi1kbu",
                  "recipient": "测试用户",
                  "phone": "13800000000",
                  "address": "北京市 海淀区 测试路1号",
                  "postal_code": "100000",
                  "card_version": "202603",
                  "status": "待双方寄出"
                }
              ]
            }
            """);

        var result = (QslAiService.OnlineImportParseResult) method.invoke(service, json, "202602");

        assertEquals(1, result.rows().size());
        var row = result.rows().get(0);
        assertEquals("BI1KBU", row.callSign());
        assertEquals("测试用户", row.recipientName());
        assertEquals("13800000000", row.telephone());
        assertEquals("100000", row.postalCode());
        assertEquals("202603", row.cardVersion());
    }

    @Test
    void shouldCompleteSingleOnlineImportMissingFieldsFromRawText() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod(
            "completeSingleOnlineImportFromRawText",
            QslAiService.OnlineImportParseResult.class,
            String.class
        );
        method.setAccessible(true);
        var result = new QslAiService.OnlineImportParseResult(
            java.util.List.of(new QslAiService.OnlineImportParsedRow(
                "BI1KBU",
                "待双方寄出",
                "测试用户",
                "",
                "北京市海淀区测试路1号",
                "",
                "",
                "202603"
            )),
            "AI解析完成：1 条"
        );
        var rawText = "收件地址：北京市海淀区测试路1号，邮编：100000，收件人：测试用户，手机号：13800000000，呼号：BI1KBU";

        var completed = (QslAiService.OnlineImportParseResult) method.invoke(service, result, rawText);

        var row = completed.rows().get(0);
        assertEquals("13800000000", row.telephone());
        assertEquals("100000", row.postalCode());
    }

    @Test
    void shouldParseCallbookAddressFromChineseAliasFields() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod(
            "parseCallbookAddress",
            String.class,
            String.class,
            com.fasterxml.jackson.databind.JsonNode.class
        );
        method.setAccessible(true);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = mapper.readTree("""
            {
              "呼号": "BA1AA",
              "姓名": "童效勇",
              "地址": "北京朝阳区工体东路8号 康堡花园 B座3单元501室",
              "邮编": "100020",
              "邮箱": "",
              "置信度": "high",
              "说明": "QRZ.CN"
            }
            """);

        var result = (QslAiService.CallbookAddressParseResult) method.invoke(service, "BA1AA", "QRZ_CN", json);

        assertEquals("BA1AA", result.callSign());
        assertEquals("童效勇", result.recipientName());
        assertEquals("100020", result.postalCode());
        assertEquals("北京朝阳区工体东路8号 康堡花园 B座3单元501室", result.address());
        assertEquals(0.9, result.confidence());
    }
}
