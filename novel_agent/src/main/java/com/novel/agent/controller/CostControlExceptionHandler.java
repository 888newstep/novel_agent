package com.novel.agent.controller;

import com.novel.agent.exception.CostLimitExceededException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class CostControlExceptionHandler {

    @ExceptionHandler(CostLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleCostLimitExceeded(CostLimitExceededException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of(
                        "success", false,
                        "error", "COST_LIMIT_EXCEEDED",
                        "message", ex.getMessage()
                ));
    }
}
