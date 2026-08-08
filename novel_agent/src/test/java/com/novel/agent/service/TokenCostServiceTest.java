package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import com.novel.agent.exception.CostLimitExceededException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenCostServiceTest {

    @Test
    void recordsChatUsageAndSummaries() {
        AiProperties properties = new AiProperties();
        properties.getCostControl().setRecentRecords(10);
        properties.getCostControl().getPricing().setInputPerMillionTokens(1000000.0);
        properties.getCostControl().getPricing().setOutputPerMillionTokens(1000000.0);
        properties.getCostControl().getPricing().setEmbeddingPerMillionTokens(1000000.0);

        TokenCostService service = new TokenCostService(properties);

        TokenCostService.UsageReservation reservation = service.reserveChatRequest(
                "deepseek",
                "deepseek-chat",
                "chat.generate",
                "hello novel world",
                20
        );
        service.recordChatSuccess(reservation, "generated content", 5, 7);

        List<TokenCostService.UsageRecord> records = service.getRecentRecords(10);
        assertEquals(1, records.size());
        assertEquals("SUCCESS", records.get(0).getStatus());
        assertEquals(5, records.get(0).getInputTokens());
        assertEquals(7, records.get(0).getOutputTokens());
        assertEquals(12, records.get(0).getTotalTokens());

        TokenCostService.DashboardSummary summary = service.getDashboardSummary();
        assertEquals(1, summary.getToday().getRequestCount());
        assertEquals(1, summary.getToday().getSuccessCount());
        assertEquals(12, summary.getToday().getBillableTokens());
        assertTrue(summary.getToday().getEstimatedCostUsd() > 0);
    }

    @Test
    void blocksRequestWhenStrictLimitIsExceeded() {
        AiProperties properties = new AiProperties();
        properties.getCostControl().setStrictMode(true);
        properties.getCostControl().setMaxEstimatedTokensPerRequest(5);

        TokenCostService service = new TokenCostService(properties);

        assertThrows(CostLimitExceededException.class, () -> service.reserveChatRequest(
                "deepseek",
                "deepseek-chat",
                "chat.generate",
                "this prompt is definitely too long for the configured limit",
                0
        ));

        List<TokenCostService.UsageRecord> records = service.getRecentRecords(1);
        assertEquals(1, records.size());
        assertEquals("BLOCKED", records.get(0).getStatus());
    }
}


