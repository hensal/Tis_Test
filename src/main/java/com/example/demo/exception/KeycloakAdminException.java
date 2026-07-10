package com.example.demo.exception;

import org.springframework.http.HttpStatus;

public class KeycloakAdminException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public KeycloakAdminException(
            HttpStatus status,
            String errorCode,
            String message
    ) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}