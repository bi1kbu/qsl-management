package com.bi1kbu.qslmanagement.api;

public record ApiResult<T>(
    String code,
    String message,
    T data
) {
    public static <T> ApiResult<T> success(T data) {
        return new ApiResult<>("QSL-0000", "成功", data);
    }

    public static <T> ApiResult<T> failure(String code, String message) {
        return new ApiResult<>(code, message, null);
    }
}
