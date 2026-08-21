package com.novel.agent.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Slf4j
public class AdminApiKeyInterceptor implements HandlerInterceptor {

    public static final String API_KEY_HEADER = "X-Admin-Api-Key";
    private final AdminSecurityProperties securityProperties;

    public AdminApiKeyInterceptor(AdminSecurityProperties securityProperties) {
        this.securityProperties = securityProperties;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        if (!securityProperties.isConfigured()) {
            reject(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "admin API key is not configured");
            log.warn("Admin API key is not configured: method={}, uri={}", request.getMethod(), request.getRequestURI());
            return false;
        }
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (!constantTimeEquals(securityProperties.getAdminApiKey(), providedKey)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "invalid admin API key");
            log.warn("Invalid admin API key: method={}, uri={}", request.getMethod(), request.getRequestURI());
            return false;
        }
        return true;
    }

    private boolean constantTimeEquals(String expectedKey, String providedKey) {
        byte[] expected = expectedKey.getBytes(StandardCharsets.UTF_8);
        byte[] provided = providedKey == null ? new byte[0] : providedKey.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, provided);
    }

    private void reject(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setContentType(MediaType.TEXT_PLAIN_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(message);
    }
}
