package com.example.demo.exception;

import com.example.demo.dto.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(KeycloakAdminException.class)
    public ResponseEntity<ApiResponse<Void>> handleKeycloakAdminError(
            KeycloakAdminException exception
    ) {
        return ResponseEntity
                .status(exception.getStatus())
                .body(ApiResponse.error(
                        exception.getErrorCode(),
                        exception.getMessage()
                ));
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(
            Exception exception
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(
                        "invalid_token",
                        "ログアウトに必要な認証情報が不正です"
                ));
    }
}
