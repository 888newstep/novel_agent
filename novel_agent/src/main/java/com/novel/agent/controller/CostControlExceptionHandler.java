package com.novel.agent.controller;

import com.novel.agent.exception.ApiErrorResponse;
import com.novel.agent.exception.CostLimitExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
public class CostControlExceptionHandler {

    @ExceptionHandler(CostLimitExceededException.class)
    public ResponseEntity<ApiErrorResponse> handleCostLimitExceeded(CostLimitExceededException ex, HttpServletRequest request) {
        log.warn("Cost limit blocked request, path={}, message={}", request.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiErrorResponse.of("COST_LIMIT_EXCEEDED", ex.getMessage(), request.getRequestURI()));
    }
}

