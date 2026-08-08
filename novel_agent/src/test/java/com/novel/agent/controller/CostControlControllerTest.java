package com.novel.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.exception.CostLimitExceededException;
import com.novel.agent.service.TokenCostService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CostControlController.class)
@AutoConfigureMockMvc
@Import(CostControlExceptionHandler.class)
class CostControlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private TokenCostService tokenCostService;

    @Test
    void returnsCostSummary() throws Exception {
        TokenCostService.UsageWindow today = new TokenCostService.UsageWindow();
        today.setRequestCount(2);
        today.setSuccessCount(1);
        today.setBillableTokens(120);
        today.setEstimatedCostUsd(0.25);

        TokenCostService.SettingsSnapshot settings = TokenCostService.SettingsSnapshot.builder()
                .enabled(true)
                .strictMode(false)
                .recentRecords(100)
                .maxEstimatedTokensPerRequest(12000)
                .reservedCompletionTokens(1200)
                .dailyTokenBudget(300000)
                .monthlyTokenBudget(5000000)
                .dailyBudgetUsd(5.0)
                .monthlyBudgetUsd(100.0)
                .currency("USD")
                .inputPerMillionTokens(1.0)
                .outputPerMillionTokens(2.0)
                .embeddingPerMillionTokens(3.0)
                .build();

        TokenCostService.DashboardSummary summary = TokenCostService.DashboardSummary.builder()
                .settings(settings)
                .today(today)
                .month(today)
                .total(today)
                .dailyTrend(List.of())
                .build();

        when(tokenCostService.getDashboardSummary()).thenReturn(summary);

        mockMvc.perform(get("/api/admin/cost/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.requestCount").value(2))
                .andExpect(jsonPath("$.settings.strictMode").value(false));
    }

    @Test
    void clearsRecords() throws Exception {
        mockMvc.perform(delete("/api/admin/cost/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void mapsCostLimitExceptionToTooManyRequests() throws Exception {
        TokenCostService.SettingsUpdateRequest request = new TokenCostService.SettingsUpdateRequest();
        request.setStrictMode(true);
        doThrow(new CostLimitExceededException("limit exceeded")).when(tokenCostService).updateSettings(any());

        mockMvc.perform(put("/api/admin/cost/settings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("COST_LIMIT_EXCEEDED"));
    }
}

