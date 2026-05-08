package com.bi1kbu.qslmanagement.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.function.server.ServerResponse;

public final class QslApiResponses {

    private static final Logger log = LoggerFactory.getLogger(QslApiResponses.class);

    private QslApiResponses() {
    }

    public static <T> Mono<ServerResponse> ok(T data) {
        return ServerResponse.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ApiResult.success(data));
    }

    public static Mono<ServerResponse> fail(HttpStatus status, String code, String message) {
        return ServerResponse.status(status)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ApiResult.failure(code, message));
    }

    public static Mono<ServerResponse> handleError(Throwable error) {
        if (error instanceof QslApiException qslApiException) {
            return fail(
                qslApiException.getStatus(),
                qslApiException.getCode(),
                qslApiException.getMessage()
            );
        }

        if (error instanceof ResponseStatusException responseStatusException) {
            var statusCode = responseStatusException.getStatusCode();
            if (statusCode.value() == HttpStatus.UNAUTHORIZED.value()) {
                return fail(HttpStatus.UNAUTHORIZED, "QSL-401-0001", "未认证");
            }
            if (statusCode.value() == HttpStatus.FORBIDDEN.value()) {
                return fail(HttpStatus.FORBIDDEN, "QSL-403-0001", "无权限");
            }
            return fail(
                HttpStatus.valueOf(statusCode.value()),
                "QSL-400-0001",
                responseStatusException.getReason() == null ? "请求参数不合法" : responseStatusException.getReason()
            );
        }

        log.error("QSL API 未捕获异常", error);
        return fail(HttpStatus.INTERNAL_SERVER_ERROR, "QSL-500-0001", "服务端内部错误");
    }
}
