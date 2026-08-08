package com.novel.agent.exception;

public record ApiErrorResponse(
        boolean success,
        String error,
        String message,
        String path,
        long timestamp
) {

    public static ApiErrorResponse of(String error, String message, String path) {
        return new ApiErrorResponse(false, error, message, path, System.currentTimeMillis());
    }
}

