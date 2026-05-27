package com.bi1kbu.qslmanagement.api;

import com.bi1kbu.qslmanagement.extension.model.SystemSetting;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

public class QslQrzAddressLookupService {

    private static final String SYSTEM_SETTING_NAME = "qsl-system-setting-default";
    private static final String QRZ_COM = "QRZ_COM";
    private static final String QRZ_CN = "QRZ_CN";
    private static final String DEFAULT_QRZ_COM_SECRET_NAME = "qsl-qrz-com-credential";
    private static final String DEFAULT_QRZ_CN_SECRET_NAME = "qsl-qrz-cn-credential";
    private static final String DEFAULT_QRZ_COM_BASE_URL = "https://xmldata.qrz.com/xml/current/";
    private static final String DEFAULT_QRZ_CN_LOOKUP_URL = "https://www.qrz.cn/call/{callSign}";
    private static final String SECRET_PASSWORD = "password";
    private static final String SECRET_COOKIE = "cookie";
    private static final int MAX_FEATURE_LENGTH = 20_000;
    private static final int MAX_QRZ_CN_TEXT_LENGTH = 8_000;
    private static final int MAX_QRZ_CN_IMAGE_TOKENS = 80;
    private static final int MAX_QRZ_CN_FETCH_ATTEMPTS = 5;
    private static final Duration QRZ_CN_FETCH_RETRY_DELAY = Duration.ofMillis(800);
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern BODY_PATTERN = Pattern.compile("(?is)<body[^>]*>(.*?)</body>");
    private static final Pattern MAIN_BLOCK_PATTERN = Pattern.compile(
        "(?is)<(main|article|section|div)[^>]*(id|class)\\s*=\\s*['\"][^'\"]*(content|main|profile|callsign|user|info|detail|card)[^'\"]*['\"][^>]*>(.*?)</\\1>"
    );
    private static final Pattern IMG_PATTERN = Pattern.compile("(?is)<img\\b([^>]*)>");
    private static final Pattern ATTR_PATTERN = Pattern.compile("(?is)(alt|title|src)\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern CHARSET_PATTERN = Pattern.compile("(?is)charset\\s*=\\s*['\"]?([a-z0-9._\\-]+)");

    private final ReactiveExtensionClient client;
    private final QslAuditService auditService;
    private final QslAiService aiService;
    public QslQrzAddressLookupService(
        ReactiveExtensionClient client,
        QslAuditService auditService,
        QslAiService aiService
    ) {
        this.client = client;
        this.auditService = auditService;
        this.aiService = aiService;
    }

    public Mono<QrzCredentialTestResult> testAndSaveCredential(
        QrzCredentialCommand command,
        String operator,
        String clientIp
    ) {
        var provider = normalizeProvider(command == null ? "" : command.provider());
        var payload = command == null ? QrzCredentialCommand.empty(provider) : command;
        return fetchOrCreateSystemSetting()
            .flatMap(systemSetting -> {
                var spec = systemSetting.getSpec() == null
                    ? new SystemSetting.SystemSettingSpec()
                    : systemSetting.getSpec();
                var workingSpec = copyQrzConfig(spec);
                applyConfig(workingSpec, provider, payload);
                var secretName = secretNameFor(provider, workingSpec);
                var testCallSign = QslApiSupport.normalizeCallSign(payload.testCallSign());
                return loadCredentialSecret(secretName)
                    .map(current -> Boolean.TRUE.equals(payload.saveCredential())
                        ? mergeCredentialSecret(current, payload.password(), payload.cookie())
                        : current)
                    .flatMap(candidateSecret -> {
                        var testMono = testCallSign.isBlank()
                            ? Mono.just(new QrzCredentialTestResult(
                                true,
                                provider,
                                "QRZ 配置已保存，未执行远程查询测试",
                                QslApiSupport.nowText()
                            ))
                            : fetchFeature(provider, testCallSign, workingSpec, candidateSecret, false)
                                .map(feature -> new QrzCredentialTestResult(
                                    true,
                                    provider,
                                    "QRZ 远程查询测试通过",
                                    QslApiSupport.nowText()
                                ));
                        return testMono.flatMap(result -> {
                            applyConfig(spec, provider, payload);
                            systemSetting.setSpec(spec);
                            var secretMono = Boolean.TRUE.equals(payload.saveCredential())
                                ? upsertCredentialSecret(secretName, payload.password(), payload.cookie())
                                : Mono.empty();
                            return client.update(systemSetting).then(secretMono).thenReturn(result);
                        });
                    });
            })
            .flatMap(result -> auditService.appendAuditLog(
                "测试QRZ配置",
                "system-setting",
                SYSTEM_SETTING_NAME,
                result.message(),
                safeOperator(operator),
                clientIp
            ).thenReturn(result));
    }

    private SystemSetting.SystemSettingSpec copyQrzConfig(SystemSetting.SystemSettingSpec source) {
        var spec = new SystemSetting.SystemSettingSpec();
        if (source == null) {
            return spec;
        }
        spec.setQrzComEnabled(source.getQrzComEnabled());
        spec.setQrzComUsername(source.getQrzComUsername());
        spec.setQrzComSecretName(source.getQrzComSecretName());
        spec.setQrzComXmlBaseUrl(source.getQrzComXmlBaseUrl());
        spec.setQrzCnEnabled(source.getQrzCnEnabled());
        spec.setQrzCnUsername(source.getQrzCnUsername());
        spec.setQrzCnSecretName(source.getQrzCnSecretName());
        spec.setQrzCnLookupUrlTemplate(source.getQrzCnLookupUrlTemplate());
        spec.setQrzTimeoutSeconds(source.getQrzTimeoutSeconds());
        return spec;
    }

    private CredentialSecret mergeCredentialSecret(CredentialSecret current, String password, String cookie) {
        return new CredentialSecret(
            normalize(password).isBlank() ? current.password() : normalize(password),
            normalize(cookie).isBlank() ? current.cookie() : normalize(cookie)
        );
    }

    public Mono<QrzAddressLookupResult> lookupAddress(QrzAddressLookupCommand command) {
        var provider = normalizeProvider(command == null ? "" : command.provider());
        var callSign = QslApiSupport.normalizeCallSign(command == null ? "" : command.callSign());
        if (callSign.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.BAD_REQUEST, "QSL-400-0001", "呼号不能为空"));
        }
        return loadSystemSettingSpec()
            .flatMap(spec -> {
                if (QRZ_COM.equals(provider) && !Boolean.TRUE.equals(spec.getQrzComEnabled())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "QRZ.COM 查询未启用"));
                }
                if (QRZ_CN.equals(provider) && !Boolean.TRUE.equals(spec.getQrzCnEnabled())) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "QRZ.CN 查询未启用"));
                }
                return fetchFeature(provider, callSign, spec);
            })
            .flatMap(feature -> aiService.parseCallbookAddressFeatures(
                new QslAiService.CallbookAddressParseCommand(provider, callSign, feature)
            ))
            .map(parsed -> new QrzAddressLookupResult(
                parsed.callSign(),
                provider,
                parsed.recipientName(),
                parsed.telephone(),
                parsed.postalCode(),
                parsed.address(),
                parsed.email(),
                parsed.confidence(),
                parsed.message(),
                QslApiSupport.nowText()
            ));
    }

    private Mono<String> fetchFeature(String provider, String callSign, SystemSetting.SystemSettingSpec spec) {
        return fetchFeature(provider, callSign, spec, null, true);
    }

    private Mono<String> fetchFeature(
        String provider,
        String callSign,
        SystemSetting.SystemSettingSpec spec,
        CredentialSecret credentialOverride,
        boolean persistRefreshedCookie
    ) {
        return QRZ_COM.equals(provider)
            ? fetchQrzComFeature(callSign, spec, credentialOverride)
            : fetchQrzCnFeature(callSign, spec, credentialOverride, persistRefreshedCookie);
    }

    private Mono<String> fetchQrzComFeature(
        String callSign,
        SystemSetting.SystemSettingSpec spec,
        CredentialSecret credentialOverride
    ) {
        var username = normalize(spec.getQrzComUsername());
        if (username.isBlank()) {
            return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "QRZ.COM 用户名未配置"));
        }
        var credentialMono = credentialOverride == null
            ? loadCredentialSecret(defaultIfBlank(spec.getQrzComSecretName(), DEFAULT_QRZ_COM_SECRET_NAME))
            : Mono.just(credentialOverride);
        return credentialMono
            .flatMap(secret -> {
                var password = normalize(secret.password());
                if (password.isBlank()) {
                    return Mono.error(new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "QSL-422-0001", "QRZ.COM 密码未配置"));
                }
                return qrzComSessionKey(spec, username, password)
                    .flatMap(sessionKey -> qrzComLookupXml(spec, sessionKey, callSign));
            })
            .map(xml -> compactFeature("QRZ.COM 官方 XML", callSign, xml));
    }

    private Mono<String> qrzComSessionKey(SystemSetting.SystemSettingSpec spec, String username, String password) {
        var url = qrzComBaseUrl(spec)
            + "?username=" + urlEncode(username)
            + "&password=" + urlEncode(password);
        return sendTextRequest(url, "", timeoutSeconds(spec))
            .map(body -> {
                var error = xmlText(body, "Error");
                if (!error.isBlank()) {
                    throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.COM 登录失败：" + error);
                }
                var key = xmlText(body, "Key");
                if (key.isBlank()) {
                    throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.COM 未返回会话 Key");
                }
                return key;
            });
    }

    private Mono<String> qrzComLookupXml(SystemSetting.SystemSettingSpec spec, String sessionKey, String callSign) {
        var url = qrzComBaseUrl(spec)
            + "?s=" + urlEncode(sessionKey)
            + "&callsign=" + urlEncode(callSign);
        return sendTextRequest(url, "", timeoutSeconds(spec))
            .map(body -> {
                var error = xmlText(body, "Error");
                if (!error.isBlank()) {
                    throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.COM 查询失败：" + error);
                }
                return body;
            });
    }

    private Mono<String> fetchQrzCnFeature(
        String callSign,
        SystemSetting.SystemSettingSpec spec,
        CredentialSecret credentialOverride,
        boolean persistRefreshedCookie
    ) {
        var urlTemplate = defaultIfBlank(spec.getQrzCnLookupUrlTemplate(), DEFAULT_QRZ_CN_LOOKUP_URL);
        var url = urlTemplate.replace("{callSign}", urlEncode(callSign));
        var secretName = defaultIfBlank(spec.getQrzCnSecretName(), DEFAULT_QRZ_CN_SECRET_NAME);
        validateQrzCnLookupUrl(url);
        var credentialMono = credentialOverride == null ? loadCredentialSecret(secretName) : Mono.just(credentialOverride);
        return credentialMono
            .flatMap(secret -> resolveQrzCnCookie(url, spec, secretName, secret, persistRefreshedCookie)
                .flatMap(cookie -> fetchQrzCnHtmlWithRetry(url, cookie, timeoutSeconds(spec), callSign)))
            .map(html -> compactQrzCnHtmlFeature(callSign, html));
    }

    private Mono<String> resolveQrzCnCookie(
        String lookupUrl,
        SystemSetting.SystemSettingSpec spec,
        String secretName,
        CredentialSecret secret,
        boolean persistRefreshedCookie
    ) {
        var username = normalize(spec.getQrzCnUsername());
        var password = normalize(secret.password());
        var savedCookie = normalize(secret.cookie());
        if (username.isBlank() || password.isBlank()) {
            return Mono.just(savedCookie);
        }
        return qrzCnLoginCookie(lookupUrl, username, password, timeoutSeconds(spec))
            .flatMap(loginCookie -> {
                var mergedCookie = mergeCookieHeaders(savedCookie, loginCookie);
                return persistRefreshedCookie
                    ? upsertCredentialSecret(secretName, "", mergedCookie).thenReturn(mergedCookie)
                    : Mono.just(mergedCookie);
            })
            .onErrorResume(error -> savedCookie.isBlank() ? Mono.error(error) : Mono.just(savedCookie));
    }

    private Mono<String> qrzCnLoginCookie(String lookupUrl, String username, String password, int timeoutSeconds) {
        try {
            URI.create(qrzCnLoginUrl(lookupUrl));
            return Mono.fromCallable(() -> qrzCnLoginCookieBlocking(lookupUrl, username, password, timeoutSeconds))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> error instanceof QslApiException
                    ? Mono.error(error)
                    : Mono.error(remoteRequestException(error)));
        } catch (Exception error) {
            return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.CN 登录请求构造失败"));
        }
    }

    private String qrzCnLoginCookieBlocking(
        String lookupUrl,
        String username,
        String password,
        int timeoutSeconds
    ) throws IOException {
        var jar = new LinkedHashMap<String, String>();
        sendTextRequestBlocking(qrzCnCallSearchFormUrl(lookupUrl), "", timeoutSeconds, jar);

        var form = new LinkedHashMap<String, String>();
        form.put("user", username);
        form.put("passwd", password);
        form.put("login.x", "10");
        form.put("login.y", "10");
        var body = sendFormPostRequestBlocking(qrzCnLoginUrl(lookupUrl), cookieHeader(jar), timeoutSeconds, form, jar);
        if (!isQrzCnLoggedInPage(body) && !cookieHeader(jar).toUpperCase().contains("CFID=")) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.CN 登录未返回有效 Cookie");
        }
        var cookie = cookieHeader(jar);
        if (cookie.isBlank()) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ.CN 登录未返回 Cookie");
        }
        return cookie;
    }

    private Mono<String> fetchQrzCnHtmlWithRetry(String url, String cookie, int timeoutSeconds, String callSign) {
        var candidates = qrzCnRequestCandidates(url, cookie, timeoutSeconds, callSign);
        return tryQrzCnRequestCandidates(candidates, callSign, 1);
    }

    private List<Supplier<Mono<String>>> qrzCnRequestCandidates(
        String url,
        String cookie,
        int timeoutSeconds,
        String callSign
    ) {
        var candidates = new ArrayList<Supplier<Mono<String>>>();
        candidates.add(() -> sendQrzCnTextRequestWithProtocolFallback(url, cookie, timeoutSeconds));
        if (isQrzCnCallSearchUrl(url)) {
            var formUrl = qrzCnCallSearchFormUrl(url);
            candidates.add(() -> sendQrzCnCallSearchRequest(formUrl, cookie, timeoutSeconds, "q", callSign));
            candidates.add(() -> sendQrzCnCallSearchRequest(formUrl, cookie, timeoutSeconds, "callsign", callSign));
        }
        return candidates;
    }

    private Mono<String> tryQrzCnRequestCandidates(
        List<Supplier<Mono<String>>> candidates,
        String callSign,
        int attempt
    ) {
        return Flux.fromIterable(candidates)
            .concatMap(candidate -> candidate.get().onErrorResume(error -> Mono.empty()))
            .filter(html -> isUsefulQrzCnHtml(html, callSign))
            .next()
            .switchIfEmpty(Mono.defer(() -> {
                if (attempt >= MAX_QRZ_CN_FETCH_ATTEMPTS) {
                    return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
                        "QRZ.CN 未返回目标呼号有效资料，可能是登录态失效、站点返回搜索页或第三方站点暂时异常"));
                }
                return Mono.delay(QRZ_CN_FETCH_RETRY_DELAY)
                    .then(tryQrzCnRequestCandidates(candidates, callSign, attempt + 1));
            }));
    }

    private Mono<String> sendQrzCnTextRequestWithProtocolFallback(String url, String cookie, int timeoutSeconds) {
        return sendTextRequest(url, cookie, timeoutSeconds)
            .onErrorResume(error -> {
                var message = normalize(error.getMessage());
                if (url.startsWith("https://") && message.contains("TLS")) {
                    return sendTextRequest("http://" + url.substring("https://".length()), cookie, timeoutSeconds);
                }
                return Mono.error(error);
            });
    }

    private Mono<String> sendQrzCnCallSearchRequest(
        String url,
        String cookie,
        int timeoutSeconds,
        String fieldName,
        String callSign
    ) {
        var form = new LinkedHashMap<String, String>();
        form.put(fieldName, normalize(callSign));
        form.put("btn_callsign.x", "10");
        form.put("btn_callsign.y", "10");
        return sendFormPostRequest(url, cookie, timeoutSeconds, form);
    }

    private Mono<String> sendTextRequest(String url, String cookie, int timeoutSeconds) {
        try {
            URI.create(url);
            return Mono.fromCallable(() -> sendTextRequestBlocking(url, cookie, timeoutSeconds))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> error instanceof QslApiException
                    ? Mono.error(error)
                    : Mono.error(remoteRequestException(error)));
        } catch (Exception error) {
            return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ 请求构造失败"));
        }
    }

    private Mono<String> sendFormPostRequest(String url, String cookie, int timeoutSeconds, Map<String, String> form) {
        try {
            URI.create(url);
            return Mono.fromCallable(() -> sendFormPostRequestBlocking(url, cookie, timeoutSeconds, form))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(error -> error instanceof QslApiException
                    ? Mono.error(error)
                    : Mono.error(remoteRequestException(error)));
        } catch (Exception error) {
            return Mono.error(new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ 请求构造失败"));
        }
    }

    private String sendTextRequestBlocking(String url, String cookie, int timeoutSeconds) throws IOException {
        return sendTextRequestBlocking(url, cookie, timeoutSeconds, null);
    }

    private String sendTextRequestBlocking(
        String url,
        String cookie,
        int timeoutSeconds,
        Map<String, String> cookieJar
    ) throws IOException {
        var connection = (HttpURLConnection) requireAllowedQrzRequestUrl(url).toURL().openConnection();
        var timeoutMillis = Math.max(5, timeoutSeconds) * 1000;
        connection.setConnectTimeout(Math.min(timeoutMillis, 10_000));
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QSL-Management-System/2.2");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        connection.setRequestProperty("Cache-Control", "no-cache");
        var cookieHeader = mergeCookieHeaders(cookieHeader(cookieJar), cookie);
        if (!cookieHeader.isBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader);
        }
        var statusCode = connection.getResponseCode();
        collectResponseCookies(connection, cookieJar);
        if (statusCode < 200 || statusCode >= 300) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ 远程查询失败：" + statusCode);
        }
        try (InputStream stream = connection.getInputStream()) {
            return decodeResponse(stream.readAllBytes(), connection.getContentType());
        } finally {
            connection.disconnect();
        }
    }

    private String sendFormPostRequestBlocking(
        String url,
        String cookie,
        int timeoutSeconds,
        Map<String, String> form
    ) throws IOException {
        return sendFormPostRequestBlocking(url, cookie, timeoutSeconds, form, null);
    }

    private String sendFormPostRequestBlocking(
        String url,
        String cookie,
        int timeoutSeconds,
        Map<String, String> form,
        Map<String, String> cookieJar
    ) throws IOException {
        var connection = (HttpURLConnection) requireAllowedQrzRequestUrl(url).toURL().openConnection();
        var timeoutMillis = Math.max(5, timeoutSeconds) * 1000;
        connection.setConnectTimeout(Math.min(timeoutMillis, 10_000));
        connection.setReadTimeout(timeoutMillis);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) QSL-Management-System/2.2");
        connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml,text/xml;q=0.9,*/*;q=0.8");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("Origin", "http://www.qrz.cn");
        connection.setRequestProperty("Referer", qrzCnCallSearchFormUrl(url));
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        var cookieHeader = mergeCookieHeaders(cookieHeader(cookieJar), cookie);
        if (!cookieHeader.isBlank()) {
            connection.setRequestProperty("Cookie", cookieHeader);
        }
        var body = form.entrySet().stream()
            .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
            .reduce((left, right) -> left + "&" + right)
            .orElse("");
        try (var output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        var statusCode = connection.getResponseCode();
        collectResponseCookies(connection, cookieJar);
        if (statusCode < 200 || statusCode >= 300) {
            throw new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001", "QRZ 远程查询失败：" + statusCode);
        }
        try (InputStream stream = connection.getInputStream()) {
            return decodeResponse(stream.readAllBytes(), connection.getContentType());
        } finally {
            connection.disconnect();
        }
    }

    private String decodeResponse(byte[] bytes, String contentType) {
        var charset = detectCharset(bytes, contentType);
        return new String(bytes, charset);
    }

    private Charset detectCharset(byte[] bytes, String contentType) {
        var contentTypeCharset = firstMatch(CHARSET_PATTERN, normalize(contentType), 1);
        if (!contentTypeCharset.isBlank()) {
            return charsetOrUtf8(contentTypeCharset);
        }
        var head = new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.ISO_8859_1);
        var htmlCharset = firstMatch(CHARSET_PATTERN, head, 1);
        return htmlCharset.isBlank() ? StandardCharsets.UTF_8 : charsetOrUtf8(htmlCharset);
    }

    private Charset charsetOrUtf8(String charsetName) {
        try {
            return Charset.forName(charsetName);
        } catch (Exception ignored) {
            return StandardCharsets.UTF_8;
        }
    }

    private QslApiException remoteRequestException(Throwable error) {
        var cause = unwrap(error);
        if (cause instanceof SocketTimeoutException) {
            return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
                "QRZ 远程请求超时，请检查网络、第三方站点状态或代理配置");
        }
        if (cause instanceof SSLException) {
            return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
                "QRZ 远程站点 TLS 握手失败，请改用 HTTP 查询地址或检查第三方站点协议支持");
        }
        var message = normalize(cause.getMessage());
        return new QslApiException(HttpStatus.BAD_GATEWAY, "QSL-502-0001",
            "QRZ 远程请求失败" + (message.isBlank() ? "" : "：" + message));
    }

    private Throwable unwrap(Throwable error) {
        if (error.getCause() != null && error.getClass().getName().contains("Completion")) {
            return unwrap(error.getCause());
        }
        return error;
    }

    private Mono<SystemSetting> fetchOrCreateSystemSetting() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .switchIfEmpty(Mono.defer(() -> {
                var systemSetting = new SystemSetting();
                systemSetting.setMetadata(QslApiSupport.createMetadata(SYSTEM_SETTING_NAME));
                systemSetting.setSpec(new SystemSetting.SystemSettingSpec());
                return client.create(systemSetting);
            }));
    }

    private Mono<SystemSetting.SystemSettingSpec> loadSystemSettingSpec() {
        return client.fetch(SystemSetting.class, SYSTEM_SETTING_NAME)
            .map(SystemSetting::getSpec)
            .map(spec -> spec == null ? new SystemSetting.SystemSettingSpec() : spec)
            .defaultIfEmpty(new SystemSetting.SystemSettingSpec());
    }

    private void applyConfig(
        SystemSetting.SystemSettingSpec spec,
        String provider,
        QrzCredentialCommand payload
    ) {
        spec.setQrzTimeoutSeconds(normalizeTimeout(payload.timeoutSeconds()));
        if (QRZ_COM.equals(provider)) {
            spec.setQrzComEnabled(Boolean.TRUE.equals(payload.enabled()));
            spec.setQrzComUsername(normalize(payload.username()));
            spec.setQrzComSecretName(defaultIfBlank(payload.secretName(), DEFAULT_QRZ_COM_SECRET_NAME));
            spec.setQrzComXmlBaseUrl(normalizeQrzComBaseUrl(payload.baseUrl()));
            return;
        }
        spec.setQrzCnEnabled(Boolean.TRUE.equals(payload.enabled()));
        spec.setQrzCnUsername(normalize(payload.username()));
        spec.setQrzCnSecretName(defaultIfBlank(payload.secretName(), DEFAULT_QRZ_CN_SECRET_NAME));
        spec.setQrzCnLookupUrlTemplate(normalizeQrzCnLookupUrlTemplate(payload.lookupUrlTemplate()));
    }

    private Mono<CredentialSecret> loadCredentialSecret(String secretName) {
        return client.fetch(Secret.class, secretName)
            .map(secret -> new CredentialSecret(readSecretValue(secret, SECRET_PASSWORD), readSecretValue(secret,
                SECRET_COOKIE)))
            .defaultIfEmpty(new CredentialSecret("", ""));
    }

    private Mono<Void> upsertCredentialSecret(String secretName, String password, String cookie) {
        var safeSecretName = defaultIfBlank(secretName, DEFAULT_QRZ_COM_SECRET_NAME);
        return loadCredentialSecret(safeSecretName)
            .flatMap(current -> client.fetch(Secret.class, safeSecretName)
                .flatMap(secret -> {
                    secret.setType(Secret.SECRET_TYPE_OPAQUE);
                    secret.setStringData(secretData(current, password, cookie));
                    return client.update(secret).thenReturn(true);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var secret = new Secret();
                    secret.setMetadata(QslApiSupport.createMetadata(safeSecretName));
                    secret.setType(Secret.SECRET_TYPE_OPAQUE);
                    secret.setStringData(secretData(current, password, cookie));
                    return client.create(secret).thenReturn(true);
                }))
                .then());
    }

    private Map<String, String> secretData(CredentialSecret current, String password, String cookie) {
        var data = new LinkedHashMap<String, String>();
        data.put(SECRET_PASSWORD, normalize(password).isBlank() ? current.password() : normalize(password));
        data.put(SECRET_COOKIE, normalize(cookie).isBlank() ? current.cookie() : normalize(cookie));
        return data;
    }

    private String readSecretValue(Secret secret, String key) {
        if (secret.getStringData() != null && secret.getStringData().containsKey(key)) {
            return normalize(secret.getStringData().get(key));
        }
        if (secret.getData() == null || !secret.getData().containsKey(key)) {
            return "";
        }
        var raw = new String(secret.getData().get(key), StandardCharsets.UTF_8).trim();
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

    private String xmlText(String xml, String tagName) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setExpandEntityReferences(false);
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            var nodes = document.getElementsByTagName(tagName);
            if (nodes.getLength() <= 0 || !(nodes.item(0) instanceof Element element)) {
                return "";
            }
            return normalize(element.getTextContent());
        } catch (Exception ignored) {
            return "";
        }
    }

    private String stripHtml(String html) {
        return normalize(html)
            .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
            .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", " ")
            .replaceAll("&nbsp;", " ")
            .replaceAll("&amp;", "&")
            .replaceAll("\\s+", " ")
            .trim();
    }

    String compactQrzCnHtmlFeature(String callSign, String html) {
        var safeHtml = normalize(html);
        var title = stripHtml(firstMatch(TITLE_PATTERN, safeHtml, 1));
        var body = firstMatch(BODY_PATTERN, safeHtml, 1);
        if (body.isBlank()) {
            body = safeHtml;
        }
        var mainBlocks = extractMainBlocks(body);
        var callSignBlock = extractCallSignBlock(body, callSign);
        var subjectHtml = !mainBlocks.isBlank() ? mainBlocks : (callSignBlock.isBlank() ? body : callSignBlock);
        var text = limit(stripHtml(subjectHtml), MAX_QRZ_CN_TEXT_LENGTH);
        var imageTokens = extractImageTokens(subjectHtml);
        var feature = new StringBuilder();
        feature.append("来源：QRZ.CN 查询页面预处理\n");
        feature.append("呼号：").append(callSign).append("\n");
        if (!title.isBlank()) {
            feature.append("标题：").append(title).append("\n");
        }
        if (!imageTokens.isEmpty()) {
            feature.append("图片文字线索：").append(String.join("；", imageTokens)).append("\n");
        }
        feature.append("主体文本：\n").append(text);
        return limit(feature.toString(), MAX_FEATURE_LENGTH);
    }

    private String extractCallSignBlock(String html, String callSign) {
        var safeHtml = normalize(html);
        var normalizedCallSign = normalize(callSign).toUpperCase();
        if (safeHtml.isBlank() || normalizedCallSign.isBlank()) {
            return "";
        }
        var upperHtml = safeHtml.toUpperCase();
        var index = upperHtml.indexOf(normalizedCallSign);
        if (index < 0) {
            return "";
        }
        var table = enclosingElement(safeHtml, upperHtml, index, "table");
        if (!table.isBlank()) {
            return table;
        }
        var div = enclosingElement(safeHtml, upperHtml, index, "div");
        if (!div.isBlank()) {
            return div;
        }
        var start = Math.max(0, index - 2_000);
        var end = Math.min(safeHtml.length(), index + 6_000);
        return safeHtml.substring(start, end);
    }

    private String enclosingElement(String html, String upperHtml, int index, String tagName) {
        var open = upperHtml.lastIndexOf("<" + tagName.toUpperCase(), index);
        var close = upperHtml.indexOf("</" + tagName.toUpperCase() + ">", index);
        if (open < 0 || close < 0 || close <= open) {
            return "";
        }
        close += tagName.length() + 3;
        return html.substring(open, Math.min(close, html.length()));
    }

    boolean isUsefulQrzCnHtml(String html, String callSign) {
        var text = (stripHtml(html) + " " + String.join(" ", extractImageTokens(html))).toUpperCase();
        var normalizedCallSign = normalize(callSign).toUpperCase();
        return !text.isBlank()
            && (normalizedCallSign.isBlank() || text.contains(normalizedCallSign))
            && !text.contains("请登录")
            && !text.contains("没有在我们的数据库中")
            && !text.contains("如果你知道,请点这里添加该呼号信息")
            && !text.contains("如果你知道，请点这里添加该呼号信息")
            && !text.contains("LOGIN");
    }

    boolean isQrzCnLoggedInPage(String html) {
        var text = stripHtml(html);
        return text.contains("退出") || text.contains("控制面板") || text.toUpperCase().contains("LOGOUT");
    }

    private String extractMainBlocks(String html) {
        var blocks = new ArrayList<String>();
        var matcher = MAIN_BLOCK_PATTERN.matcher(html);
        while (matcher.find()) {
            var block = normalize(matcher.group(4));
            if (!block.isBlank()) {
                blocks.add(block);
            }
        }
        if (blocks.isEmpty()) {
            return "";
        }
        blocks.sort((left, right) -> Integer.compare(right.length(), left.length()));
        return String.join("\n", blocks.subList(0, Math.min(3, blocks.size())));
    }

    private List<String> extractImageTokens(String html) {
        var tokens = new LinkedHashSet<String>();
        var imageMatcher = IMG_PATTERN.matcher(html);
        while (imageMatcher.find() && tokens.size() < MAX_QRZ_CN_IMAGE_TOKENS) {
            var attributes = imageMatcher.group(1);
            var attrMatcher = ATTR_PATTERN.matcher(attributes);
            while (attrMatcher.find() && tokens.size() < MAX_QRZ_CN_IMAGE_TOKENS) {
                var value = normalize(htmlDecode(attrMatcher.group(2)));
                if (value.isBlank()) {
                    continue;
                }
                if ("src".equalsIgnoreCase(attrMatcher.group(1))) {
                    value = imageFileName(value);
                }
                if (!value.isBlank()) {
                    tokens.add(value);
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    private String firstMatch(Pattern pattern, String text, int group) {
        var matcher = pattern.matcher(text);
        return matcher.find() ? normalize(matcher.group(group)) : "";
    }

    private String imageFileName(String value) {
        var normalized = normalize(value);
        var queryIndex = normalized.indexOf('?');
        if (queryIndex >= 0) {
            normalized = normalized.substring(0, queryIndex);
        }
        var slashIndex = Math.max(normalized.lastIndexOf('/'), normalized.lastIndexOf('\\'));
        if (slashIndex >= 0 && slashIndex < normalized.length() - 1) {
            normalized = normalized.substring(slashIndex + 1);
        }
        normalized = normalized.replaceAll("(?i)\\.(png|jpg|jpeg|gif|webp|bmp|svg)$", "");
        normalized = normalized.replace('_', ' ').replace('-', ' ');
        try {
            normalized = URLDecoder.decode(normalized, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            // 文件名不是有效 URL 编码时保留原始内容。
        }
        return normalize(normalized);
    }

    private String htmlDecode(String value) {
        return normalize(value)
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'");
    }

    private String limit(String value, int maxLength) {
        var normalized = normalize(value);
        return normalized.length() <= maxLength ? normalized : normalized.substring(0, maxLength);
    }

    private String compactFeature(String source, String callSign, String content) {
        var feature = "来源：" + source + "\n呼号：" + callSign + "\n内容：\n" + normalize(content);
        return feature.length() <= MAX_FEATURE_LENGTH ? feature : feature.substring(0, MAX_FEATURE_LENGTH);
    }

    private boolean isQrzCnCallSearchUrl(String url) {
        var normalized = normalize(url).toLowerCase();
        return normalized.contains("qrz.cn/call");
    }

    private String qrzCnCallSearchFormUrl(String url) {
        var normalized = normalize(url);
        var scheme = normalized.startsWith("https://") ? "http://" : "http://";
        var hostStart = normalized.indexOf("://");
        if (hostStart < 0) {
            return "http://www.qrz.cn/call/";
        }
        var hostEnd = normalized.indexOf('/', hostStart + 3);
        var host = hostEnd < 0 ? normalized.substring(hostStart + 3) : normalized.substring(hostStart + 3, hostEnd);
        return scheme + defaultIfBlank(host, "www.qrz.cn") + "/call/";
    }

    private String qrzCnLoginUrl(String url) {
        var normalized = normalize(url);
        var scheme = normalized.startsWith("https://") ? "http://" : "http://";
        var hostStart = normalized.indexOf("://");
        if (hostStart < 0) {
            return "http://www.qrz.cn/my/";
        }
        var hostEnd = normalized.indexOf('/', hostStart + 3);
        var host = hostEnd < 0 ? normalized.substring(hostStart + 3) : normalized.substring(hostStart + 3, hostEnd);
        return scheme + defaultIfBlank(host, "www.qrz.cn") + "/my/";
    }

    void collectResponseCookies(HttpURLConnection connection, Map<String, String> cookieJar) {
        if (connection == null || cookieJar == null) {
            return;
        }
        var headers = connection.getHeaderFields();
        if (headers == null) {
            return;
        }
        headers.forEach((name, values) -> {
            if (name == null || values == null || !"Set-Cookie".equalsIgnoreCase(name)) {
                return;
            }
            values.forEach(value -> putCookie(cookieJar, value));
        });
    }

    String mergeCookieHeaders(String left, String right) {
        var cookies = new LinkedHashMap<String, String>();
        putCookieHeader(cookies, left);
        putCookieHeader(cookies, right);
        return cookieHeader(cookies);
    }

    private void putCookieHeader(Map<String, String> cookies, String header) {
        var normalized = normalize(header);
        if (normalized.isBlank()) {
            return;
        }
        for (var item : normalized.split(";")) {
            putCookie(cookies, item);
        }
    }

    private void putCookie(Map<String, String> cookies, String rawCookie) {
        if (cookies == null) {
            return;
        }
        var normalized = normalize(rawCookie);
        if (normalized.isBlank()) {
            return;
        }
        var firstPart = normalized.split(";", 2)[0].trim();
        var equalsIndex = firstPart.indexOf('=');
        if (equalsIndex <= 0) {
            return;
        }
        var name = firstPart.substring(0, equalsIndex).trim();
        var value = firstPart.substring(equalsIndex + 1).trim();
        if (name.isBlank() || isCookieAttributeName(name)) {
            return;
        }
        cookies.put(name, value);
    }

    private boolean isCookieAttributeName(String name) {
        var normalized = normalize(name).toLowerCase();
        return normalized.equals("path")
            || normalized.equals("domain")
            || normalized.equals("expires")
            || normalized.equals("max-age")
            || normalized.equals("samesite")
            || normalized.equals("secure")
            || normalized.equals("httponly");
    }

    private String cookieHeader(Map<String, String> cookies) {
        if (cookies == null || cookies.isEmpty()) {
            return "";
        }
        var parts = new ArrayList<String>();
        cookies.forEach((name, value) -> {
            if (!normalize(name).isBlank()) {
                parts.add(name + "=" + normalize(value));
            }
        });
        return String.join("; ", parts);
    }

    private String qrzComBaseUrl(SystemSetting.SystemSettingSpec spec) {
        return normalizeQrzComBaseUrl(spec.getQrzComXmlBaseUrl());
    }

    String normalizeQrzComBaseUrl(String value) {
        var baseUrl = defaultIfBlank(value, DEFAULT_QRZ_COM_BASE_URL);
        var uri = requireAllowedQrzRequestUrl(baseUrl);
        if (!isQrzComHost(uri.getHost()) || uri.getQuery() != null || uri.getFragment() != null) {
            throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "QRZ.COM XML地址必须是 xmldata.qrz.com 的 HTTP/HTTPS 基础地址");
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/";
    }

    String normalizeQrzCnLookupUrlTemplate(String value) {
        var template = defaultIfBlank(value, DEFAULT_QRZ_CN_LOOKUP_URL);
        if (!template.contains("{callSign}")) {
            throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "QRZ.CN查询地址模板必须保留 {callSign} 占位符");
        }
        validateQrzCnLookupUrl(template.replace("{callSign}", "BI1KBU"));
        return template;
    }

    private void validateQrzCnLookupUrl(String url) {
        var uri = requireAllowedQrzRequestUrl(url);
        if (!isQrzCnHost(uri.getHost())) {
            throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "QRZ.CN查询地址必须使用 qrz.cn 官方域名");
        }
    }

    private URI requireAllowedQrzRequestUrl(String url) {
        try {
            var uri = URI.create(normalize(url));
            var scheme = normalize(uri.getScheme()).toLowerCase(Locale.ROOT);
            var host = normalize(uri.getHost()).toLowerCase(Locale.ROOT);
            if ((!scheme.equals("https") && !scheme.equals("http"))
                || host.isBlank()
                || uri.getUserInfo() != null
                || (!isQrzComHost(host) && !isQrzCnHost(host))) {
                throw new IllegalArgumentException("unsupported qrz url");
            }
            return uri;
        } catch (QslApiException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new QslApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "QSL-422-0001", "QRZ地址必须是 QRZ.COM 或 QRZ.CN 官方 HTTP/HTTPS 地址");
        }
    }

    private boolean isQrzComHost(String host) {
        return "xmldata.qrz.com".equals(normalize(host).toLowerCase(Locale.ROOT));
    }

    private boolean isQrzCnHost(String host) {
        var normalizedHost = normalize(host).toLowerCase(Locale.ROOT);
        return "qrz.cn".equals(normalizedHost) || normalizedHost.endsWith(".qrz.cn");
    }

    private String secretNameFor(String provider, SystemSetting.SystemSettingSpec spec) {
        return QRZ_COM.equals(provider)
            ? defaultIfBlank(spec.getQrzComSecretName(), DEFAULT_QRZ_COM_SECRET_NAME)
            : defaultIfBlank(spec.getQrzCnSecretName(), DEFAULT_QRZ_CN_SECRET_NAME);
    }

    private int timeoutSeconds(SystemSetting.SystemSettingSpec spec) {
        return normalizeTimeout(spec.getQrzTimeoutSeconds());
    }

    private int normalizeTimeout(Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds < 5) {
            return 30;
        }
        return Math.min(timeoutSeconds, 120);
    }

    private String normalizeProvider(String provider) {
        var normalized = normalize(provider).toUpperCase();
        if (QRZ_CN.equals(normalized)) {
            return QRZ_CN;
        }
        return QRZ_COM;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(normalize(value), StandardCharsets.UTF_8);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String defaultIfBlank(String value, String fallback) {
        var normalized = normalize(value);
        return normalized.isBlank() ? normalize(fallback) : normalized;
    }

    private String safeOperator(String operator) {
        return operator == null || operator.isBlank() ? "system" : operator.trim();
    }

    private record CredentialSecret(String password, String cookie) {
    }

    public record QrzCredentialCommand(
        String provider,
        Boolean enabled,
        String username,
        String password,
        String cookie,
        String secretName,
        String baseUrl,
        String lookupUrlTemplate,
        Integer timeoutSeconds,
        Boolean saveCredential,
        String testCallSign
    ) {
        static QrzCredentialCommand empty(String provider) {
            return new QrzCredentialCommand(provider, Boolean.FALSE, "", "", "", "", "", "", null, Boolean.FALSE, "");
        }
    }

    public record QrzCredentialTestResult(boolean success, String provider, String message, String testedAt) {
    }

    public record QrzAddressLookupCommand(String provider, String callSign) {
    }

    public record QrzAddressLookupResult(
        String callSign,
        String provider,
        String recipientName,
        String telephone,
        String postalCode,
        String address,
        String email,
        double confidence,
        String message,
        String lookedUpAt
    ) {
    }
}
