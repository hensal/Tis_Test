package com.example.demo.dto;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;

@JsonSerialize(using = ApiResponseSerializer.class)
public record ApiResponse<T>(
        boolean success,
        String error_code,
        String message,
        Object errors,
        T data
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(
                true,
                null,
                "ok",
                null,
                data
        );
    }

    public static <T> ApiResponse<T> error(
            String errorCode,
            String message
    ) {
        return new ApiResponse<>(
                false,
                errorCode,
                message,
                null,
                null
        );
    }
}