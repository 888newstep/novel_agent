package com.novel.agent.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@Slf4j
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class GlobalApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(MissingServletRequestParameterException ex,
                                                                         HttpHeaders headers,
                                                                         HttpStatusCode status,
                                                                         WebRequest request) {
        return buildError(status, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        return buildError(status, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return buildError(status, "INVALID_REQUEST", ex.getMessage(), request);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        return buildError(status, "INVALID_REQUEST", ex.getMessage(), request);
    }

    private ResponseEntity<Object> buildError(HttpStatusCode status, String error, String message, WebRequest request) {
        String path = request.getDescription(false).replace("uri=", "");
        log.warn("API request failed, path={}, message={}", path, message);
        return ResponseEntity.status(status)
                .body(ApiErrorResponse.of(error, message, path));
    }
}


