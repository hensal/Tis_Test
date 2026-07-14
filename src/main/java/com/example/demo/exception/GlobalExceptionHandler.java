package com.example.demo.exception;

import com.example.demo.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotSupportedException;
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
            Exception exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error(
                        "invalid_token",
                        invalidTokenMessage(request)
                ));
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaType() {
        return ResponseEntity
                .status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                .body(ApiResponse.error(
                        "validation_error",
                        "リクエストパラメータが不正です"
                ));
    }

    private String invalidTokenMessage(HttpServletRequest request) {
        String requestUri = request.getRequestURI();

        if (requestUri != null && requestUri.endsWith("/logout")) {
            return "ログアウトに必要な認証情報が不正です";
        }

        return "認証情報が不正です";
    }
}
