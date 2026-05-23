package com.bi1kbu.qslmanagement.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
                30000,
                Boolean.TRUE,
                Boolean.TRUE,
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
    void shouldDecodeBase64SecretData() throws Exception {
        var service = new QslAiService(mock(ReactiveExtensionClient.class), mock(QslAuditService.class));
        Method method = QslAiService.class.getDeclaredMethod("decodeSecretData", byte[].class);
        method.setAccessible(true);
        var encoded = Base64.getEncoder().encodeToString("sk-decoded".getBytes(StandardCharsets.UTF_8));

        var decoded = (String) method.invoke(service, encoded.getBytes(StandardCharsets.UTF_8));

        assertEquals("sk-decoded", decoded);
    }
}
