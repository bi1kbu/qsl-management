package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.AddressBookEntry;
import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

public class QslAiService {

    private static final ListOptions EMPTY_OPTIONS = ListOptions.builder().build();
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("metadata.creationTimestamp"));
    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String DEFAULT_SECRET_NAME = "qsl-ai-openai-api-key";
    private static final String SECRET_API_KEY = "apiKey";
    private static final int MAX_ADDRESS_ROWS = 100;
    private static final int MAX_IMPORT_TEXT_LENGTH = 30_000;
    private static final int MAX_ADDRESS_LENGTH = 500;
    private static final int MAX_PROMPT_LENGTH = 8_000;
    private static final Set<String> ONLINE_IMPORT_ALLOWED_STATUSES = Set.of("对方已寄出，待我签收", "待双方寄出");
    private static final Pattern POSTAL_CODE_PATTERN = Pattern.compile("(?:邮编|邮政编码)\\s*[:：]?\\s*([0-9]{6})");
    private static final Pattern TELEPHONE_PATTERN = Pattern.compile("(?:电话|手机号|手机)\\s*[:：]?\\s*([0-9][0-9\\-\\s]{5,20}[0-9])");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "(?:邮箱|电子邮件|E-mail|Email)\\s*[:：]?\\s*([A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,})",
        Pattern.CASE_INSENSITIVE
    );

    private final ReactiveExtensionClient client;
    private final QslAuditService auditService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final Object aiConcurrencyMonitor = new Object();
    private int activeAiRequestCount = 0;

    public QslAiService(ReactiveExtensionClient client, QslAuditService auditService) {
        this.client = client;
        this.auditService = auditService;
    }

    public Mono<AiConfigurationResponse> getConfiguration() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .defaultIfEmpty(createDefaultSystemSetting())
            .flatMap(systemSetting -> toConfigurationResponse(systemSetting));
    }

    public Mono<AiConfigurationResponse> saveConfiguration(
        AiConfigurationCommand command,
        String operator,
        String clientIp
    ) {
        var payload = command == null
            ? new AiConfigurationCommand(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
            )
            : command;
        return fetchOrCreateSystemSetting()
            .flatMap(systemSetting -> {
                var spec = systemSetting.getSpec() == null
                    ? new SystemSetting.SystemSettingSpec()
                    : systemSetting.getSpec();
                spec.setAiEnabled(payload.enabled() == null ? Boolean.FALSE : payload.enabled());
                spec.setAiProvider(defaultIfBlank(payload.provider(), "openai-compatible"));
                spec.setAiBaseUrl(normalizeBaseUrl(defaultIfBlank(payload.baseUrl(), "https://api.openai.com/v1")));
                spec.setAiModel(defaultIfBlank(payload.model(), ""));
                spec.setAiSecretName(defaultIfBlank(payload.secretName(), DEFAULT_SECRET_NAME));
                spec.setAiTemperature(normalizeTemperature(payload.temperature()));
                spec.setAiTimeoutSeconds(normalizeTimeout(payload.timeoutSeconds()));
                spec.setAiMaxConcurrentRequests(normalizeMaxConcurrentRequests(payload.maxConcurrentRequests()));
                spec.setAiMaxInputCharacters(normalizeMaxInputCharacters(payload.maxInputCharacters()));
                spec.setAiOnlineImportParseEnabled(payload.onlineImportParseEnabled() == null
                    ? Boolean.FALSE
                    : payload.onlineImportParseEnabled());
                spec.setAiAddressCleanupEnabled(payload.addressCleanupEnabled() == null
                    ? Boolean.FALSE
                    : payload.addressCleanupEnabled());
                spec.setAiSystemPrompt(normalizePrompt(payload.systemPrompt(), QslAiPromptDefaults.SYSTEM_PROMPT));
                spec.setAiOnlineImportPrompt(normalizePrompt(
                    payload.onlineImportPrompt(),
                    QslAiPromptDefaults.ONLINE_IMPORT_PROMPT
                ));
                spec.setAiAddressCleanupPrompt(normalizePrompt(
                    payload.addressCleanupPrompt(),
                    QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT
                ));
                spec.setAiCallbookAddressPrompt(normalizePrompt(
                    payload.callbookAddressPrompt(),
                    QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT
                ));
                systemSetting.setSpec(spec);
                return client.update(systemSetting)
                    .flatMap(updated -> {
                        var apiKey = normalize(payload.apiKey());
                        if (apiKey.isBlank()) {
                            return Mono.just(updated);
                        }
                        return upsertApiKeySecret(apiKey, spec.getAiSecretName()).thenReturn(updated);
                    });
            })
            .flatMap(updated -> auditService.appendAuditLog(
                "保存AI配置",
                "system-setting",
                SYSTEM_SETTING_NAME,
                "已更新AI服务非敏感配置" + (normalize(payload.apiKey()).isBlank() ? "" : "并写入Secret"),
                safeOperator(operator),
                clientIp
            ).thenReturn(updated))
            .flatMap(this::toConfigurationResponse);
    }

    public Mono<AiConfigTestResult> testConfig(AiConfigTestCommand command, String operator, String clientIp) {
        var apiKey = command == null ? "" : normalize(command.apiKey());
        return loadAiConfig(command)
            .flatMap(config -> {
                var testKeyMono = apiKey.isBlank() ? loadApiKey(config.secretName()) : Mono.just(apiKey);
                return testKeyMono.flatMap(testKey -> callOpenAiJson(
                        config,
                        testKey,
                        "请只返回 JSON：{\"ok\":true,\"message\":\"连接正常\"}",
                        testSchema(),
                        true
                    )
                    .map(json -> new AiConfigTestResult(
                        true,
                        "AI 接口连接正常",
                        config.provider(),
                        config.model(),
                        QslApiSupport.nowText()
                    ))
                    .flatMap(result -> command != null
                        && Boolean.TRUE.equals(command.saveApiKey())
                        && !apiKey.isBlank()
                        ? upsertApiKeySecret(apiKey, config.secretName()).thenReturn(result)
                        : Mono.just(result)));
            })
            .flatMap(result -> auditService.appendAuditLog(
                "测试AI配置",
                "system-setting",
                SYSTEM_SETTING_NAME,
                result.message(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    public Mono<AddressNormalizationPreviewResult> previewAddressNormalizations(
        AddressNormalizationPreviewCommand command
    ) {
        var rows = command == null || command.rows() == null ? List.<AddressNormalizationInput>of() : command.rows();
        if (rows.isEmpty()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "请选择需要整理的地址"));
        }
        if (rows.size() > MAX_ADDRESS_ROWS) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                "单次最多整理 " + MAX_ADDRESS_ROWS + " 条地址"));
        }
        return loadEffectiveAiConfig(true, false)
            .flatMap(config -> loadApiKey(config.secretName())
                .flatMap(apiKey -> callOpenAiJson(
                    config,
                    apiKey,
                    buildAddressPrompt(rows, config.addressCleanupPrompt()),
                    addressSchema(),
                    true
                )))
            .map(json -> parseAddressPreview(rows, json));
    }

    public Mono<AddressNormalizationApplyResult> applyAddressNormalizations(
        AddressNormalizationApplyCommand command,
        String operator,
        String clientIp
    ) {
        var rows = command == null || command.rows() == null ? List.<AddressNormalizationApplyItem>of() : command.rows();
        if (rows.isEmpty()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "请选择需要应用的地址"));
        }
        if (rows.size() > MAX_ADDRESS_ROWS) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                "单次最多应用 " + MAX_ADDRESS_ROWS + " 条地址"));
        }
        return Flux.fromIterable(rows)
            .concatMap(item -> applySingleAddress(item)
                .onErrorResume(error -> Mono.just(new AddressNormalizationApplyItemResult(
                    normalize(item.addressEntryName()),
                    "FAILED",
                    error.getMessage() == null ? "应用失败" : error.getMessage()
                ))))
            .collectList()
            .flatMap(results -> {
                var successCount = (int) results.stream().filter(item -> "UPDATED".equals(item.status())).count();
                var failedCount = (int) results.stream().filter(item -> "FAILED".equals(item.status())).count();
                var skippedCount = results.size() - successCount - failedCount;
                return auditService.appendAuditLog(
                    "应用AI地址整理",
                    "address-book-entry",
                    "batch",
                    "提交：" + rows.size() + "；成功：" + successCount,
                    safeOperator(operator),
                    clientIp
                ).thenReturn(new AddressNormalizationApplyResult(rows.size(), successCount, skippedCount, failedCount,
                    results));
            });
    }

    public Mono<OnlineImportParseResult> parseOnlineImport(OnlineImportParseCommand command) {
        var rawText = command == null ? "" : normalize(command.text());
        if (rawText.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "导入文本不能为空"));
        }
        return loadEffectiveAiConfig(false, true)
            .flatMap(config -> {
                if (rawText.length() > config.maxInputCharacters()) {
                    return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001",
                        "导入文本不能超过 " + config.maxInputCharacters() + " 个字符"));
                }
                return loadApiKey(config.secretName())
                    .flatMap(apiKey -> callOpenAiJson(
                        config,
                        apiKey,
                        buildOnlineImportPrompt(
                            rawText,
                            normalize(command.defaultCardVersion()),
                            normalize(command.mode()),
                            config.onlineImportPrompt()
                        ),
                        onlineImportSchema(),
                        true
                    ));
            })
            .map(json -> parseOnlineImportRows(json, normalize(command.defaultCardVersion())))
            .map(result -> completeSingleOnlineImportFromRawText(result, rawText));
    }

    public Mono<CallbookAddressParseResult> parseCallbookAddressFeatures(CallbookAddressParseCommand command) {
        var provider = normalize(command == null ? "" : command.provider());
        var callSign = QslApiSupport.normalizeCallSign(command == null ? "" : command.callSign());
        var features = normalize(command == null ? "" : command.features());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        if (features.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "待解析内容不能为空"));
        }
        return loadEffectiveAiConfig(false, false)
            .flatMap(config -> loadApiKey(config.secretName())
                .flatMap(apiKey -> callOpenAiJson(
                    config,
                    apiKey,
                    buildCallbookAddressPrompt(provider, callSign, features, config.callbookAddressPrompt()),
                    callbookAddressSchema(),
                    true
                )))
            .map(json -> parseCallbookAddress(callSign, provider, json));
    }

    private CallbookAddressParseResult parseCallbookAddress(String fallbackCallSign, String provider, JsonNode json) {
        var address = nodeText(json, "address", "addr", "qth", "地址", "通信地址", "收件地址", "中文地址", "英文地址");
        if (address.length() > MAX_ADDRESS_LENGTH) {
            address = address.substring(0, MAX_ADDRESS_LENGTH);
        }
        var confidence = nodeConfidence(json, "confidence", "置信度", "可信度");
        confidence = Math.max(0, Math.min(confidence, 1));
        return new CallbookAddressParseResult(
            defaultIfBlank(nodeText(json, "callSign", "callsign", "call_sign", "呼号"), fallbackCallSign)
                .toUpperCase(Locale.ROOT),
            provider,
            nodeText(json, "recipientName", "recipient", "name", "fullName", "recipient_name", "姓名", "收件人"),
            nodeText(json, "telephone", "phone", "mobile", "tel", "phoneNumber", "mobilePhone", "电话", "手机", "手机号"),
            nodeText(json, "postalCode", "postal_code", "zip", "zip_code", "zipcode", "postCode", "postcode", "邮编", "邮政编码"),
            address,
            nodeText(json, "email", "mail", "邮箱", "电子邮件"),
            confidence,
            nodeText(json, "message", "note", "reason", "说明", "备注", "来源")
        );
    }

    private double nodeConfidence(JsonNode node, String... fieldNames) {
        var text = nodeText(node, fieldNames);
        if (text.isBlank()) {
            return 0;
        }
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
            var normalized = text.trim().toLowerCase(Locale.ROOT);
            if (normalized.equals("high") || normalized.equals("高") || normalized.equals("较高")) {
                return 0.9;
            }
            if (normalized.equals("medium") || normalized.equals("中") || normalized.equals("一般")) {
                return 0.6;
            }
            if (normalized.equals("low") || normalized.equals("低") || normalized.equals("较低")) {
                return 0.3;
            }
            return 0;
        }
    }

    private Mono<AddressNormalizationApplyItemResult> applySingleAddress(AddressNormalizationApplyItem item) {
        var addressEntryName = normalize(item.addressEntryName());
        var normalizedAddress = normalize(item.normalizedAddress());
        if (addressEntryName.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "地址编号不能为空"));
        }
        if (normalizedAddress.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "整理后的地址不能为空"));
        }
        if (normalizedAddress.length() > MAX_ADDRESS_LENGTH) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "整理后的地址过长"));
        }
        return client.fetch(AddressBookEntry.class, addressEntryName)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.NOT_FOUND, "QSL-404-0001", "地址不存在")))
            .flatMap(entry -> {
                var spec = entry.getSpec() == null
                    ? new AddressBookEntry.AddressBookSpec()
                    : entry.getSpec();
                spec.setAddress(normalizedAddress);
                entry.setSpec(spec);
                return client.update(entry);
            })
            .map(updated -> new AddressNormalizationApplyItemResult(addressEntryName, "UPDATED", "已更新地址"));
    }

    private Mono<SystemSetting> fetchOrCreateSystemSetting() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .switchIfEmpty(Mono.defer(() -> client.create(createDefaultSystemSetting())));
    }

    private SystemSetting createDefaultSystemSetting() {
        var systemSetting = new SystemSetting();
        systemSetting.setMetadata(QslApiSupport.createMetadata(SYSTEM_SETTING_NAME));
        var spec = new SystemSetting.SystemSettingSpec();
        spec.setAiEnabled(Boolean.FALSE);
        spec.setAiProvider("openai-compatible");
        spec.setAiBaseUrl("https://api.openai.com/v1");
        spec.setAiModel("");
        spec.setAiSecretName(DEFAULT_SECRET_NAME);
        spec.setAiTemperature(0.2);
        spec.setAiTimeoutSeconds(30);
        spec.setAiMaxConcurrentRequests(1);
        spec.setAiMaxInputCharacters(MAX_IMPORT_TEXT_LENGTH);
        spec.setAiOnlineImportParseEnabled(Boolean.FALSE);
        spec.setAiAddressCleanupEnabled(Boolean.FALSE);
        spec.setAiSystemPrompt(QslAiPromptDefaults.SYSTEM_PROMPT);
        spec.setAiOnlineImportPrompt(QslAiPromptDefaults.ONLINE_IMPORT_PROMPT);
        spec.setAiAddressCleanupPrompt(QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT);
        spec.setAiCallbookAddressPrompt(QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT);
        systemSetting.setSpec(spec);
        return systemSetting;
    }

    private Mono<AiConfigurationResponse> toConfigurationResponse(SystemSetting systemSetting) {
        var spec = systemSetting.getSpec() == null ? new SystemSetting.SystemSettingSpec() : systemSetting.getSpec();
        var secretName = defaultIfBlank(spec.getAiSecretName(), DEFAULT_SECRET_NAME);
        return client.fetch(Secret.class, secretName)
            .map(secret -> !readSecretApiKey(secret).isBlank())
            .defaultIfEmpty(false)
            .map(hasApiKey -> new AiConfigurationResponse(
                Boolean.TRUE.equals(spec.getAiEnabled()),
                defaultIfBlank(spec.getAiProvider(), "openai-compatible"),
                normalizeBaseUrl(defaultIfBlank(spec.getAiBaseUrl(), "https://api.openai.com/v1")),
                normalize(spec.getAiModel()),
                secretName,
                normalizeTemperature(spec.getAiTemperature()),
                normalizeTimeout(spec.getAiTimeoutSeconds()),
                normalizeMaxConcurrentRequests(spec.getAiMaxConcurrentRequests()),
                normalizeMaxInputCharacters(spec.getAiMaxInputCharacters()),
                Boolean.TRUE.equals(spec.getAiOnlineImportParseEnabled()),
                Boolean.TRUE.equals(spec.getAiAddressCleanupEnabled()),
                normalizePrompt(spec.getAiSystemPrompt(), QslAiPromptDefaults.SYSTEM_PROMPT),
                normalizePrompt(spec.getAiOnlineImportPrompt(), QslAiPromptDefaults.ONLINE_IMPORT_PROMPT),
                normalizePrompt(spec.getAiAddressCleanupPrompt(), QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT),
                normalizePrompt(spec.getAiCallbookAddressPrompt(), QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT),
                hasApiKey
            ));
    }

    private Mono<AiRuntimeConfig> loadEffectiveAiConfig(boolean requireAddressEnabled, boolean requireImportEnabled) {
        return loadAiConfig(null)
            .flatMap(config -> {
                if (!config.enabled()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "AI 功能未启用"));
                }
                if (requireAddressEnabled && !config.addressCleanupEnabled()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "AI 地址整理未启用"));
                }
                if (requireImportEnabled && !config.onlineImportParseEnabled()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "线上换卡 AI 解析未启用"));
                }
                return Mono.just(config);
            });
    }

    private Mono<AiRuntimeConfig> loadAiConfig(AiConfigTestCommand override) {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(systemSetting -> systemSetting.getSpec() == null
                ? new SystemSetting.SystemSettingSpec()
                : systemSetting.getSpec())
            .defaultIfEmpty(new SystemSetting.SystemSettingSpec())
            .map(spec -> {
                var provider = defaultIfBlank(override == null ? "" : override.provider(), spec.getAiProvider());
                var baseUrl = defaultIfBlank(override == null ? "" : override.baseUrl(), spec.getAiBaseUrl());
                var model = defaultIfBlank(override == null ? "" : override.model(), spec.getAiModel());
                var secretName = defaultIfBlank(override == null ? "" : override.secretName(), spec.getAiSecretName());
                var temperature = override != null && override.temperature() != null
                    ? override.temperature()
                    : spec.getAiTemperature();
                var timeoutSeconds = override != null && override.timeoutSeconds() != null
                    ? override.timeoutSeconds()
                    : spec.getAiTimeoutSeconds();
                var maxConcurrentRequests = override != null && override.maxConcurrentRequests() != null
                    ? override.maxConcurrentRequests()
                    : spec.getAiMaxConcurrentRequests();
                var maxInputCharacters = spec.getAiMaxInputCharacters();
                var systemPrompt = normalizePrompt(spec.getAiSystemPrompt(), QslAiPromptDefaults.SYSTEM_PROMPT);
                var onlineImportPrompt = normalizePrompt(
                    spec.getAiOnlineImportPrompt(),
                    QslAiPromptDefaults.ONLINE_IMPORT_PROMPT
                );
                var addressCleanupPrompt = normalizePrompt(
                    spec.getAiAddressCleanupPrompt(),
                    QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT
                );
                var callbookAddressPrompt = normalizePrompt(
                    spec.getAiCallbookAddressPrompt(),
                    QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT
                );
                var enabled = Boolean.TRUE.equals(spec.getAiEnabled());
                var onlineImportParseEnabled = Boolean.TRUE.equals(spec.getAiOnlineImportParseEnabled());
                var addressCleanupEnabled = Boolean.TRUE.equals(spec.getAiAddressCleanupEnabled());
                return new AiRuntimeConfig(
                    defaultIfBlank(provider, "openai-compatible"),
                    normalizeBaseUrl(defaultIfBlank(baseUrl, "https://api.openai.com/v1")),
                    defaultIfBlank(model, ""),
                    defaultIfBlank(secretName, DEFAULT_SECRET_NAME),
                    normalizeTemperature(temperature),
                    normalizeTimeout(timeoutSeconds),
                    normalizeMaxConcurrentRequests(maxConcurrentRequests),
                    normalizeMaxInputCharacters(maxInputCharacters),
                    systemPrompt,
                    onlineImportPrompt,
                    addressCleanupPrompt,
                    callbookAddressPrompt,
                    enabled,
                    onlineImportParseEnabled,
                    addressCleanupEnabled,
                    false
                );
            })
            .flatMap(config -> client.fetch(Secret.class, config.secretName())
                .map(secret -> config.withSecretNameConfigured(true))
                .defaultIfEmpty(config));
    }

    private Mono<String> loadApiKey(String secretName) {
        var safeSecretName = defaultIfBlank(secretName, DEFAULT_SECRET_NAME);
        return client.fetch(Secret.class, safeSecretName)
            .switchIfEmpty(Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "AI API Key 未配置")))
            .map(this::readSecretApiKey)
            .flatMap(apiKey -> apiKey.isBlank()
                ? Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "QSL-422-0001", "AI API Key 未配置"))
                : Mono.just(apiKey));
    }

    private String readSecretApiKey(Secret secret) {
        if (secret.getStringData() != null && secret.getStringData().containsKey(SECRET_API_KEY)) {
            return normalize(secret.getStringData().get(SECRET_API_KEY));
        }
        if (secret.getData() == null || !secret.getData().containsKey(SECRET_API_KEY)) {
            return "";
        }
        return decodeSecretData(secret.getData().get(SECRET_API_KEY));
    }

    private Mono<Void> upsertApiKeySecret(String apiKey, String secretName) {
        var safeSecretName = defaultIfBlank(secretName, DEFAULT_SECRET_NAME);
        return client.fetch(Secret.class, safeSecretName)
            .flatMap(secret -> {
                secret.setType(Secret.SECRET_TYPE_OPAQUE);
                secret.setStringData(Map.of(SECRET_API_KEY, apiKey));
                return client.update(secret).thenReturn(true);
            })
            .switchIfEmpty(Mono.defer(() -> {
                var secret = new Secret();
                secret.setMetadata(QslApiSupport.createMetadata(safeSecretName));
                secret.setType(Secret.SECRET_TYPE_OPAQUE);
                secret.setStringData(Map.of(SECRET_API_KEY, apiKey));
                return client.create(secret).thenReturn(true);
            }))
            .then();
    }

    private Mono<JsonNode> callOpenAiJson(
        AiRuntimeConfig config,
        String apiKey,
        String prompt,
        Map<String, Object> schema,
        boolean allowJsonObjectFallback
    ) {
        if (config.model().isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY, "QSL-422-0001", "AI 模型不能为空"));
        }
        return sendOpenAiRequestWithRateLimitRetry(config, apiKey, prompt, schema, true, 0)
            .onErrorResume(error -> allowJsonObjectFallback && shouldRetryWithJsonObject(error)
                ? sendOpenAiRequestWithRateLimitRetry(config, apiKey, prompt, schema, false, 0)
                : Mono.error(error));
    }

    private Mono<JsonNode> sendOpenAiRequestWithRateLimitRetry(
        AiRuntimeConfig config,
        String apiKey,
        String prompt,
        Map<String, Object> schema,
        boolean strictSchema,
        int attempt
    ) {
        return Mono.delay(Duration.ofMillis(attempt == 0 ? 0 : 2_000L * attempt))
            .then(acquireAiRequestPermit(config))
            .then(sendOpenAiRequest(config, apiKey, prompt, schema, strictSchema)
                .doFinally(ignored -> releaseAiRequestPermit()))
            .onErrorResume(error -> shouldRetryAfterRateLimit(error) && attempt < 3
                ? sendOpenAiRequestWithRateLimitRetry(config, apiKey, prompt, schema, strictSchema, attempt + 1)
                : Mono.error(error));
    }

    private Mono<Void> acquireAiRequestPermit(AiRuntimeConfig config) {
        return Mono.fromRunnable(() -> {
            var maxConcurrentRequests = normalizeMaxConcurrentRequests(config.maxConcurrentRequests());
            synchronized (aiConcurrencyMonitor) {
                while (activeAiRequestCount >= maxConcurrentRequests) {
                    try {
                        aiConcurrencyMonitor.wait();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "AI 并发等待被中断");
                    }
                }
                activeAiRequestCount++;
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    private void releaseAiRequestPermit() {
        synchronized (aiConcurrencyMonitor) {
            activeAiRequestCount = Math.max(0, activeAiRequestCount - 1);
            aiConcurrencyMonitor.notifyAll();
        }
    }

    private Mono<JsonNode> sendOpenAiRequest(
        AiRuntimeConfig config,
        String apiKey,
        String prompt,
        Map<String, Object> schema,
        boolean strictSchema
    ) {
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("model", config.model());
            body.put("temperature", config.temperature());
            body.put("messages", List.of(
                Map.of("role", "system", "content", config.systemPrompt()),
                Map.of("role", "user", "content", prompt)
            ));
            if (strictSchema) {
                body.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                        "name", "qsl_ai_result",
                        "strict", true,
                        "schema", schema
                    )
                ));
            } else {
                body.put("response_format", Map.of("type", "json_object"));
            }
            var request = HttpRequest.newBuilder()
                .uri(URI.create(config.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(config.timeoutSeconds()))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
            return Mono.fromFuture(httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)))
                .flatMap(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY,
                            "QSL-502-0001", "AI 接口调用失败：" + response.statusCode()));
                    }
                    return Mono.just(parseOpenAiContent(response.body()));
                })
                .onErrorResume(error -> error instanceof QslApiException
                    ? Mono.error(error)
                    : Mono.error(aiRemoteRequestException(error)));
        } catch (Exception error) {
            return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "AI 请求构造失败"));
        }
    }

    private boolean shouldRetryWithJsonObject(Throwable error) {
        return error instanceof QslApiException qslApiException
            && qslApiException.getMessage() != null
            && (qslApiException.getMessage().contains("AI 接口调用失败：400")
                || qslApiException.getMessage().contains("AI 接口调用失败：422"));
    }

    private boolean shouldRetryAfterRateLimit(Throwable error) {
        return error instanceof QslApiException qslApiException
            && qslApiException.getMessage() != null
            && qslApiException.getMessage().contains("AI 接口调用失败：429");
    }

    private QslApiException aiRemoteRequestException(Throwable error) {
        var cause = unwrap(error);
        if (cause instanceof java.net.http.HttpTimeoutException) {
            return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
                "AI 接口请求超时，请检查模型、接口地址或提高超时时间");
        }
        if (cause instanceof SSLException) {
            return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
                "AI 接口 TLS 握手失败，请检查接口地址或代理配置");
        }
        var message = normalize(cause.getMessage());
        return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
            "AI 接口请求失败" + (message.isBlank() ? "" : "：" + message));
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return unwrap(error.getCause());
        }
        return error;
    }

    private JsonNode parseOpenAiContent(String responseBody) {
        try {
            var root = objectMapper.readTree(responseBody);
            var content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalArgumentException("AI 响应为空");
            }
            try {
                return objectMapper.readTree(content);
            } catch (Exception ignored) {
                return objectMapper.readTree(extractJsonObject(content));
            }
        } catch (Exception error) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0002", "AI 响应不是有效 JSON");
        }
    }

    private String extractJsonObject(String content) {
        var normalized = normalize(content)
            .replaceFirst("(?is)^```(?:json)?\\s*", "")
            .replaceFirst("(?is)\\s*```$", "")
            .trim();
        var start = normalized.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("AI 响应缺少 JSON 对象");
        }
        var depth = 0;
        var inString = false;
        var escaping = false;
        for (var index = start; index < normalized.length(); index++) {
            var current = normalized.charAt(index);
            if (escaping) {
                escaping = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaping = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return normalized.substring(start, index + 1);
                }
            }
        }
        throw new IllegalArgumentException("AI 响应 JSON 对象不完整");
    }

    private AddressNormalizationPreviewResult parseAddressPreview(List<AddressNormalizationInput> inputs, JsonNode json) {
        var byName = new LinkedHashMap<String, AddressNormalizationInput>();
        inputs.forEach(input -> byName.put(normalize(input.addressEntryName()), input));
        var results = new ArrayList<AddressNormalizationPreviewItem>();
        var items = json.path("items");
        if (!items.isArray()) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0002", "AI 地址整理结果格式不正确");
        }
        for (var item : items) {
            var name = normalize(item.path("addressEntryName").asText(""));
            var input = byName.get(name);
            if (input == null) {
                continue;
            }
            var originalAddress = normalize(input.address());
            var normalizedAddress = normalize(item.path("normalizedAddress").asText(""));
            if (normalizedAddress.length() > MAX_ADDRESS_LENGTH) {
                normalizedAddress = normalizedAddress.substring(0, MAX_ADDRESS_LENGTH);
            }
            results.add(new AddressNormalizationPreviewItem(
                name,
                normalize(input.callSign()),
                normalize(input.recipientName()),
                normalize(input.telephone()),
                normalize(input.postalCode()),
                originalAddress,
                normalize(input.email()),
                normalize(input.addressRemarks()),
                normalize(input.recipientName()),
                normalize(input.telephone()),
                normalize(input.postalCode()),
                normalizedAddress,
                normalize(input.email()),
                normalize(input.addressRemarks()),
                !originalAddress.equals(normalizedAddress),
                item.path("confidence").asDouble(0),
                normalize(item.path("message").asText(""))
            ));
        }
        var changedCount = (int) results.stream().filter(AddressNormalizationPreviewItem::changed).count();
        return new AddressNormalizationPreviewResult(inputs.size(), changedCount, results);
    }

    private OnlineImportParseResult parseOnlineImportRows(JsonNode json, String defaultCardVersion) {
        var rows = new ArrayList<OnlineImportParsedRow>();
        var items = json.path("rows");
        if (!items.isArray()) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0002", "AI 导入解析结果格式不正确");
        }
        var index = 1;
        for (var item : items) {
            var callSign = QslApiSupport.normalizeCallSign(nodeText(item, "callSign", "callsign", "call_sign"));
            if (callSign.isBlank()) {
                throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0002", "AI 导入解析结果缺少呼号");
            }
            var status = defaultIfBlank(nodeText(item, "status"), "待双方寄出");
            if (!ONLINE_IMPORT_ALLOWED_STATUSES.contains(status)) {
                throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0002", "AI 导入解析结果包含不支持的状态");
            }
            rows.add(new OnlineImportParsedRow(
                callSign,
                status,
                nodeText(item, "recipientName", "recipient", "name", "fullName", "recipient_name"),
                nodeText(item, "telephone", "phone", "mobile", "tel", "phoneNumber", "mobilePhone"),
                nodeText(item, "address", "addr", "qth"),
                nodeText(item, "postalCode", "postal_code", "zip", "zip_code", "zipcode", "postCode", "postcode"),
                nodeText(item, "email", "mail"),
                defaultIfBlank(nodeText(item, "cardVersion", "card_version", "card_version_name", "version"),
                    defaultCardVersion)
            ));
        }
        return new OnlineImportParseResult(rows, rows.isEmpty() ? "未解析到导入记录" : "AI解析完成：" + rows.size() + " 条");
    }

    private OnlineImportParseResult completeSingleOnlineImportFromRawText(
        OnlineImportParseResult result,
        String rawText
    ) {
        if (result == null || result.rows() == null || result.rows().size() != 1) {
            return result;
        }
        var row = result.rows().get(0);
        var completed = new OnlineImportParsedRow(
            row.callSign(),
            row.status(),
            row.recipientName(),
            defaultIfBlank(row.telephone(), firstGroup(TELEPHONE_PATTERN, rawText)),
            row.address(),
            defaultIfBlank(row.postalCode(), firstGroup(POSTAL_CODE_PATTERN, rawText)),
            defaultIfBlank(row.email(), firstGroup(EMAIL_PATTERN, rawText)),
            row.cardVersion()
        );
        return new OnlineImportParseResult(List.of(completed), result.message());
    }

    private String firstGroup(Pattern pattern, String text) {
        var matcher = pattern.matcher(normalize(text));
        return matcher.find() ? normalize(matcher.group(1)) : "";
    }

    private String nodeText(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return "";
        }
        for (var fieldName : fieldNames) {
            var value = node.path(fieldName);
            if (!value.isMissingNode() && !value.isNull()) {
                var text = normalize(value.asText(""));
                if (!text.isBlank() && !"null".equalsIgnoreCase(text)) {
                    return text;
                }
            }
        }
        return "";
    }

    private String buildAddressPrompt(List<AddressNormalizationInput> rows, String promptTemplate) {
        try {
            var rowsJson = objectMapper.writeValueAsString(rows);
            var prompt = normalizePrompt(promptTemplate, QslAiPromptDefaults.ADDRESS_CLEANUP_PROMPT)
                .replace("{rows}", rowsJson);
            return prompt.contains(rowsJson) ? prompt : prompt + "\n输入：\n" + rowsJson;
        } catch (Exception error) {
            throw new QslApiException(HttpStatus.INTERNAL_SERVER_ERROR, "QSL-500-0001", "地址整理请求构造失败");
        }
    }

    private String buildOnlineImportPrompt(
        String rawText,
        String defaultCardVersion,
        String mode,
        String promptTemplate
    ) {
        var prompt = normalizePrompt(promptTemplate, QslAiPromptDefaults.ONLINE_IMPORT_PROMPT)
            .replace("{defaultCardVersion}", defaultCardVersion)
            .replace("{mode}", mode)
            .replace("{text}", rawText);
        return prompt.contains(rawText) ? prompt : prompt + "\n文本：\n" + rawText;
    }

    private String buildCallbookAddressPrompt(
        String provider,
        String callSign,
        String features,
        String promptTemplate
    ) {
        var prompt = normalizePrompt(promptTemplate, QslAiPromptDefaults.CALLBOOK_ADDRESS_PROMPT)
            .replace("{provider}", provider)
            .replace("{callSign}", callSign)
            .replace("{features}", features);
        return prompt.contains(features) ? prompt : prompt + "\n内容：\n" + features;
    }

    private Map<String, Object> addressSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("items"),
            "properties", Map.of("items", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of("addressEntryName", "normalizedAddress", "confidence", "message"),
                    "properties", Map.of(
                        "addressEntryName", Map.of("type", "string"),
                        "normalizedAddress", Map.of("type", "string"),
                        "confidence", Map.of("type", "number"),
                        "message", Map.of("type", "string")
                    )
                )
            ))
        );
    }

    private Map<String, Object> onlineImportSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("rows"),
            "properties", Map.of("rows", Map.of(
                "type", "array",
                "items", Map.of(
                    "type", "object",
                    "additionalProperties", false,
                    "required", List.of(
                        "callSign", "status", "recipientName", "telephone", "address", "postalCode", "email",
                        "cardVersion"
                    ),
                    "properties", Map.of(
                        "callSign", Map.of("type", "string"),
                        "status", Map.of("type", "string"),
                        "recipientName", Map.of("type", "string"),
                        "telephone", Map.of("type", "string"),
                        "address", Map.of("type", "string"),
                        "postalCode", Map.of("type", "string"),
                        "email", Map.of("type", "string"),
                        "cardVersion", Map.of("type", "string")
                    )
                )
            ))
        );
    }

    private Map<String, Object> callbookAddressSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of(
                "callSign", "recipientName", "telephone", "postalCode", "address", "email", "confidence",
                "message"
            ),
            "properties", Map.of(
                "callSign", Map.of("type", "string"),
                "recipientName", Map.of("type", "string"),
                "telephone", Map.of("type", "string"),
                "postalCode", Map.of("type", "string"),
                "address", Map.of("type", "string"),
                "email", Map.of("type", "string"),
                "confidence", Map.of("type", "number"),
                "message", Map.of("type", "string")
            )
        );
    }

    private Map<String, Object> testSchema() {
        return Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("ok", "message"),
            "properties", Map.of(
                "ok", Map.of("type", "boolean"),
                "message", Map.of("type", "string")
            )
        );
    }

    private String normalizeBaseUrl(String baseUrl) {
        var normalized = normalize(baseUrl);
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds < 5) {
            return 30;
        }
        return Math.min(timeoutSeconds, 120);
    }

    private int normalizeMaxConcurrentRequests(Integer maxConcurrentRequests) {
        if (maxConcurrentRequests == null || maxConcurrentRequests < 1) {
            return 1;
        }
        return Math.min(maxConcurrentRequests, 10);
    }

    private double normalizeTemperature(Double temperature) {
        if (temperature == null || !Double.isFinite(temperature)) {
            return 0.2;
        }
        return Math.max(0, Math.min(temperature, 2));
    }

    private int normalizeMaxInputCharacters(Integer maxInputCharacters) {
        if (maxInputCharacters == null || maxInputCharacters < 1000) {
            return MAX_IMPORT_TEXT_LENGTH;
        }
        return Math.min(maxInputCharacters, 100_000);
    }

    private String normalizePrompt(String value, String fallback) {
        var normalized = normalize(value);
        if (normalized.isBlank()) {
            normalized = normalize(fallback);
        }
        if (normalized.length() <= MAX_PROMPT_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PROMPT_LENGTH);
    }

    private String decodeSecretData(byte[] data) {
        var raw = new String(data, StandardCharsets.UTF_8).trim();
        if (raw.isBlank()) {
            return "";
        }
        try {
            var decoded = new String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8).trim();
            return decoded.isBlank() ? raw : decoded;
        } catch (IllegalArgumentException ignored) {
            return raw;
        }
    }

    private String defaultIfBlank(String value, String fallback) {
        var normalized = normalize(value);
        return normalized.isBlank() ? normalize(fallback) : normalized;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String safeOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator.trim();
    }

    public record AiConfigurationCommand(
        Boolean enabled,
        String provider,
        String baseUrl,
        String model,
        String secretName,
        String apiKey,
        Double temperature,
        Integer timeoutSeconds,
        Integer maxConcurrentRequests,
        Integer maxInputCharacters,
        Boolean onlineImportParseEnabled,
        Boolean addressCleanupEnabled,
        String systemPrompt,
        String onlineImportPrompt,
        String addressCleanupPrompt,
        String callbookAddressPrompt
    ) {
    }

    public record AiConfigurationResponse(
        Boolean enabled,
        String provider,
        String baseUrl,
        String model,
        String secretName,
        Double temperature,
        Integer timeoutSeconds,
        Integer maxConcurrentRequests,
        Integer maxInputCharacters,
        Boolean onlineImportParseEnabled,
        Boolean addressCleanupEnabled,
        String systemPrompt,
        String onlineImportPrompt,
        String addressCleanupPrompt,
        String callbookAddressPrompt,
        Boolean hasApiKey
    ) {
    }

    public record AiConfigTestCommand(
        String provider,
        String baseUrl,
        String model,
        String secretName,
        Double temperature,
        Integer timeoutSeconds,
        Integer maxConcurrentRequests,
        String apiKey,
        Boolean saveApiKey
    ) {
    }

    public record AiConfigTestResult(boolean success, String message, String provider, String model, String testedAt) {
    }

    public record AddressNormalizationPreviewCommand(List<AddressNormalizationInput> rows) {
    }

    public record AddressNormalizationInput(
        String addressEntryName,
        String callSign,
        String recipientName,
        String telephone,
        String postalCode,
        String address,
        String email,
        String addressRemarks
    ) {
    }

    public record AddressNormalizationPreviewResult(
        int totalCount,
        int changedCount,
        List<AddressNormalizationPreviewItem> rows
    ) {
    }

    public record AddressNormalizationPreviewItem(
        String addressEntryName,
        String callSign,
        String originalRecipientName,
        String originalTelephone,
        String originalPostalCode,
        String originalAddress,
        String originalEmail,
        String originalAddressRemarks,
        String normalizedRecipientName,
        String normalizedTelephone,
        String normalizedPostalCode,
        String normalizedAddress,
        String normalizedEmail,
        String normalizedAddressRemarks,
        boolean changed,
        double confidence,
        String message
    ) {
    }

    public record AddressNormalizationApplyCommand(List<AddressNormalizationApplyItem> rows) {
    }

    public record AddressNormalizationApplyItem(
        String addressEntryName,
        String callSign,
        String originalRecipientName,
        String originalTelephone,
        String originalPostalCode,
        String originalAddress,
        String originalEmail,
        String originalAddressRemarks,
        String normalizedRecipientName,
        String normalizedTelephone,
        String normalizedPostalCode,
        String normalizedAddress,
        String normalizedEmail,
        String normalizedAddressRemarks,
        Double confidence,
        Boolean changed,
        String message
    ) {
    }

    public record AddressNormalizationApplyResult(
        int totalCount,
        int successCount,
        int skippedCount,
        int failedCount,
        List<AddressNormalizationApplyItemResult> results
    ) {
    }

    public record AddressNormalizationApplyItemResult(String addressEntryName, String status, String message) {
    }

    public record OnlineImportParseCommand(String mode, String text, String defaultCardVersion) {
    }

    public record OnlineImportParseResult(List<OnlineImportParsedRow> rows, String message) {
    }

    public record OnlineImportParsedRow(
        String callSign,
        String status,
        String recipientName,
        String telephone,
        String address,
        String postalCode,
        String email,
        String cardVersion
    ) {
    }

    public record CallbookAddressParseCommand(String provider, String callSign, String features) {
    }

    public record CallbookAddressParseResult(
        String callSign,
        String provider,
        String recipientName,
        String telephone,
        String postalCode,
        String address,
        String email,
        double confidence,
        String message
    ) {
    }

    private record AiRuntimeConfig(
        String provider,
        String baseUrl,
        String model,
        String secretName,
        double temperature,
        int timeoutSeconds,
        int maxConcurrentRequests,
        int maxInputCharacters,
        String systemPrompt,
        String onlineImportPrompt,
        String addressCleanupPrompt,
        String callbookAddressPrompt,
        boolean enabled,
        boolean onlineImportParseEnabled,
        boolean addressCleanupEnabled,
        boolean secretNameConfigured
    ) {
        AiRuntimeConfig withSecretNameConfigured(boolean value) {
            return new AiRuntimeConfig(
                provider,
                baseUrl,
                model,
                secretName,
                temperature,
                timeoutSeconds,
                maxConcurrentRequests,
                maxInputCharacters,
                systemPrompt,
                onlineImportPrompt,
                addressCleanupPrompt,
                callbookAddressPrompt,
                enabled,
                onlineImportParseEnabled,
                addressCleanupEnabled,
                value
            );
        }
    }
}
