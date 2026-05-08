package com.bi1kbu.qslmanagement.api;

import org.springframework.http.HttpStatus;

public class QslApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public QslApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
