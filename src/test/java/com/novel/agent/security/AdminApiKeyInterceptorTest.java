package com.novel.agent.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminApiKeyInterceptorTest {

    @Test
    void rejectsWhenApiKeyIsNotConfigured() throws Exception {
        AdminSecurityProperties properties = propertiesWithKey(" ");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor(properties).preHandle(request("GET"), response, new Object());

        assertFalse(accepted);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString()).contains("not configured");
    }

    @Test
    void rejectsMissingApiKey() throws Exception {
        AdminSecurityProperties properties = propertiesWithKey("expected-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor(properties).preHandle(request("POST"), response, new Object());

        assertFalse(accepted);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("invalid admin API key");
    }

    @Test
    void rejectsWrongApiKeyWithoutEchoingProvidedValue() throws Exception {
        AdminSecurityProperties properties = propertiesWithKey("expected-secret");
        MockHttpServletRequest request = request("POST");
        request.addHeader(AdminApiKeyInterceptor.API_KEY_HEADER, "attacker-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean accepted = interceptor(properties).preHandle(request, response, new Object());

        assertFalse(accepted);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).doesNotContain("attacker-secret");
    }

    @Test
    void acceptsCorrectApiKey() throws Exception {
        AdminSecurityProperties properties = propertiesWithKey("expected-secret");
        MockHttpServletRequest request = request("POST");
        request.addHeader(AdminApiKeyInterceptor.API_KEY_HEADER, "expected-secret");

        boolean accepted = interceptor(properties).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(accepted);
    }

    @Test
    void allowsOptionsPreflightEvenWhenKeyIsNotConfigured() throws Exception {
        MockHttpServletRequest request = request("OPTIONS");
        AdminSecurityProperties properties = propertiesWithKey("");

        boolean accepted = interceptor(properties).preHandle(request, new MockHttpServletResponse(), new Object());

        assertTrue(accepted);
    }

    private AdminApiKeyInterceptor interceptor(AdminSecurityProperties properties) {
        return new AdminApiKeyInterceptor(properties);
    }

    private AdminSecurityProperties propertiesWithKey(String key) {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.setAdminApiKey(key);
        return properties;
    }

    private MockHttpServletRequest request(String method) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setMethod(method);
        request.setRequestURI("/api/import/finalize");
        return request;
    }
}
