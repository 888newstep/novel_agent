package com.novel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvaluationServiceTest {

    @Test
    void evaluatesRecallPrecisionAndCategoryBreakdown() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationService service = new RagEvaluationService(milvusSearchService, new ObjectMapper());
        service.init();

        int queryCount = service.getTestCases().size();
        AtomicInteger callIndex = new AtomicInteger();
        when(milvusSearchService.searchSegments(eq(0L), anyString(), eq(3))).thenAnswer(invocation -> {
            String query = invocation.getArgument(1, String.class);
            int currentCall = callIndex.getAndIncrement();
            if (currentCall < queryCount) {
                return List.of(Map.of("content", query, "score", 0.98));
            }
            return List.of(Map.of("content", "irrelevant result", "score", 0.10));
        });

        RagEvaluationService.EvaluationReport firstReport = service.evaluate(0L, 3);
        RagEvaluationService.EvaluationReport secondReport = service.evaluate(0L, 3);

        assertEquals(queryCount, firstReport.getQueryCount());
        assertEquals(queryCount, firstReport.getDetails().size());
        assertEquals(5, firstReport.getCategorySummaries().size());
        assertEquals(100.0, firstReport.getRecallAtK(), 0.0001);
        assertEquals(100.0, firstReport.getPrecisionAtK(), 0.0001);
        assertEquals(1.0, firstReport.getMrr(), 0.0001);
        double expectedContextChars = service.getTestCases().stream()
                .mapToInt(testCase -> testCase.getQuery().length())
                .average()
                .orElseThrow();
        assertEquals(expectedContextChars, firstReport.getAvgRetrievedContextChars(), 0.0001);
        assertEquals(Math.ceil(expectedContextChars / 4.0), firstReport.getAvgRetrievedContextTokens(), 0.0001);
        assertTrue(firstReport.getP95LatencyMs() >= 0.0);
        assertEquals("writing-default-v1", firstReport.getProfileName());
        assertEquals("2026-08-09", firstReport.getDatasetVersion());
        assertNull(firstReport.getComparison());
        assertEquals(1, firstReport.getHistory().size());

        assertEquals(queryCount, secondReport.getQueryCount());
        assertEquals(0.0, secondReport.getRecallAtK(), 0.0001);
        assertEquals(0.0, secondReport.getPrecisionAtK(), 0.0001);
        assertEquals(0.0, secondReport.getMrr(), 0.0001);
        assertNotNull(secondReport.getComparison());
        assertEquals(-100.0, secondReport.getComparison().getRecallAtKDelta(), 0.0001);
        assertEquals(-1.0, secondReport.getComparison().getMrrDelta(), 0.0001);
        assertEquals(2, secondReport.getHistory().size());
    }
}
