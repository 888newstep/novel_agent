package com.novel.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.entity.RagEvaluationSnapshot;
import com.novel.agent.repository.RagEvaluationSnapshotRepository;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

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

    @Test
    void loadsChineseLiveProfileAndKeepsHistoryIndependent() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationService service = new RagEvaluationService(milvusSearchService, new ObjectMapper());
        service.init();

        assertFalse(service.getAvailableProfiles().isEmpty());
        assertTrue(service.getAvailableProfiles().contains(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME));
        assertEquals(15, service.getTestCases(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME).size());
        assertEquals("2026-08-10", service.getDatasetVersion(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME));

        when(milvusSearchService.searchSegments(anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> List.of(Map.of(
                        "content", invocation.getArgument(1, String.class),
                        "score", 0.99
                )));

        RagEvaluationService.EvaluationReport chineseReport = service.evaluate(
                0L, 3, RagEvaluationService.CHINESE_LIVE_PROFILE_NAME);
        RagEvaluationService.EvaluationReport defaultReport = service.evaluate(0L, 3);

        assertEquals(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME, chineseReport.getProfileName());
        assertEquals("2026-08-10", chineseReport.getDatasetVersion());
        assertNull(chineseReport.getComparison());
        assertEquals(1, chineseReport.getHistory().size());
        assertEquals(RagEvaluationService.DEFAULT_PROFILE_NAME, defaultReport.getProfileName());
        assertEquals("2026-08-09", defaultReport.getDatasetVersion());
        assertNull(defaultReport.getComparison());
        assertEquals(1, defaultReport.getHistory().size());
        assertEquals(chineseReport, service.getLastReport(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME));
        assertEquals(defaultReport, service.getLastReport());
    }

    @Test
    void returnsExplicitEmptyReportForUnknownProfile() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationService service = new RagEvaluationService(milvusSearchService, new ObjectMapper());
        service.init();

        RagEvaluationService.EvaluationReport report = service.evaluate(0L, 3, "missing-profile");

        assertEquals("missing-profile", report.getProfileName());
        assertNull(report.getDatasetVersion());
        assertEquals(0, report.getQueryCount());
        assertTrue(report.getHistory().isEmpty());
        assertTrue(report.getReason().contains("Unknown evaluation profile"));
    }

    @Test
    void persistsAggregateSnapshotWithoutQueryDetails() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationSnapshotRepository repository = mock(RagEvaluationSnapshotRepository.class);
        when(repository.findAllByOrderByEvaluatedAtDesc(any(Pageable.class))).thenReturn(List.of());
        when(milvusSearchService.searchSegments(anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> List.of(Map.of(
                        "content", invocation.getArgument(1, String.class),
                        "score", 0.99
                )));

        RagEvaluationService service = new RagEvaluationService(
                milvusSearchService, new ObjectMapper(), repository);
        service.init();
        RagEvaluationService.EvaluationReport report = service.evaluate(
                9L, 3, RagEvaluationService.CHINESE_LIVE_PROFILE_NAME);

        ArgumentCaptor<RagEvaluationSnapshot> captor = ArgumentCaptor.forClass(RagEvaluationSnapshot.class);
        verify(repository).save(captor.capture());
        RagEvaluationSnapshot snapshot = captor.getValue();
        assertEquals(9L, snapshot.getNovelId());
        assertEquals(report.getProfileName(), snapshot.getProfileName());
        assertEquals(report.getDatasetVersion(), snapshot.getDatasetVersion());
        assertEquals(report.getQueryCount(), snapshot.getQueryCount());
        assertEquals(report.getRecallAtK(), snapshot.getRecallAtK(), 0.0001);
        assertEquals(report.getAvgRetrievedContextChars(), snapshot.getAvgContextChars(), 0.0001);
        assertNotNull(report.getDetails());
    }

    @Test
    void databasePersistenceFailureDoesNotBlockEvaluation() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationSnapshotRepository repository = mock(RagEvaluationSnapshotRepository.class);
        when(repository.findAllByOrderByEvaluatedAtDesc(any(Pageable.class))).thenReturn(List.of());
        doThrow(new RuntimeException("database unavailable"))
                .when(repository).save(any(RagEvaluationSnapshot.class));
        when(milvusSearchService.searchSegments(anyLong(), anyString(), anyInt()))
                .thenAnswer(invocation -> List.of(Map.of(
                        "content", invocation.getArgument(1, String.class),
                        "score", 0.99
                )));

        RagEvaluationService service = new RagEvaluationService(
                milvusSearchService, new ObjectMapper(), repository);
        service.init();

        RagEvaluationService.EvaluationReport report = service.evaluate(0L, 3);

        assertEquals(15, report.getQueryCount());
        assertEquals(100.0, report.getRecallAtK(), 0.0001);
    }

    @Test
    void restoresLatestAggregateSnapshotAfterServiceRestart() {
        MilvusSearchService milvusSearchService = mock(MilvusSearchService.class);
        RagEvaluationSnapshotRepository repository = mock(RagEvaluationSnapshotRepository.class);
        RagEvaluationSnapshot persisted = RagEvaluationSnapshot.builder()
                .profileName(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME)
                .datasetVersion("2026-08-10")
                .novelId(0L)
                .topK(5)
                .queryCount(15)
                .queriesWithRelevantResult(11)
                .recallAtK(73.33)
                .precisionAtK(39.56)
                .mrr(0.689)
                .avgLatencyMs(111.6)
                .p95LatencyMs(240.0)
                .p99LatencyMs(240.0)
                .minLatencyMs(90.0)
                .maxLatencyMs(260.0)
                .keywordCoverage(80.0)
                .avgContextChars(480.0)
                .avgContextTokens(120.0)
                .evaluatedAt(123L)
                .build();
        when(repository.findAllByOrderByEvaluatedAtDesc(any(Pageable.class)))
                .thenReturn(List.of(persisted));

        RagEvaluationService service = new RagEvaluationService(
                milvusSearchService, new ObjectMapper(), repository);
        service.init();

        RagEvaluationService.EvaluationReport report = service.getLastReport(
                0L, RagEvaluationService.CHINESE_LIVE_PROFILE_NAME);

        assertNotNull(report);
        assertEquals(73.33, report.getRecallAtK(), 0.0001);
        assertEquals(39.56, report.getPrecisionAtK(), 0.0001);
        assertEquals(0, report.getDetails().size());
        assertEquals(1, report.getHistory().size());
    }
}
