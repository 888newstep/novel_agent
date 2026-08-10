package com.novel.agent.controller;

import com.novel.agent.service.RagEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RagEvaluationController.class)
@AutoConfigureMockMvc
@Import(CostControlExceptionHandler.class)
class RagEvaluationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RagEvaluationService ragEvaluationService;

    @Test
    void evaluatesSegmentsWhenDatasetExists() throws Exception {
        RagEvaluationService.TestCase testCase = new RagEvaluationService.TestCase();
        testCase.setQuery("dragon foreshadowing");
        testCase.setCategory("plot");
        testCase.setExpectedKeywords(List.of("dragon"));

        RagEvaluationService.EvaluationReport report = new RagEvaluationService.EvaluationReport(
                1L,
                1,
                3,
                100.0,
                100.0,
                1.0,
                10.0,
                10.0,
                10.0,
                10.0,
                10.0,
                100.0,
                1,
                List.of()
        );

        when(ragEvaluationService.getTestCases()).thenReturn(List.of(testCase));
        when(ragEvaluationService.evaluate(anyLong(), anyInt())).thenReturn(report);

        mockMvc.perform(post("/api/v1/novel/evaluate/segments")
                        .param("novelId", "0")
                        .param("topK", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(1))
                .andExpect(jsonPath("$.recallAtK").value(100.0));
    }

    @Test
    void evaluatesSelectedProfileWithoutChangingDefaultContract() throws Exception {
        RagEvaluationService.EvaluationReport report = RagEvaluationService.EvaluationReport.empty(
                RagEvaluationService.CHINESE_LIVE_PROFILE_NAME,
                "2026-08-10",
                null);
        report.setReason(null);
        report.setCategorySummaries(List.of());
        report.setHistory(List.of());

        when(ragEvaluationService.evaluate(anyLong(), anyInt(),
                eq(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME))).thenReturn(report);

        mockMvc.perform(post("/api/v1/novel/evaluate/segments")
                        .param("novelId", "0")
                        .param("topK", "5")
                        .param("profile", RagEvaluationService.CHINESE_LIVE_PROFILE_NAME))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profileName").value(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME))
                .andExpect(jsonPath("$.datasetVersion").value("2026-08-10"));
    }

    @Test
    void returnsAvailableEvaluationProfiles() throws Exception {
        when(ragEvaluationService.getAvailableProfiles()).thenReturn(List.of(
                RagEvaluationService.DEFAULT_PROFILE_NAME,
                RagEvaluationService.CHINESE_LIVE_PROFILE_NAME
        ));
        when(ragEvaluationService.getDatasetVersion(anyString())).thenReturn("2026-08-10");
        when(ragEvaluationService.getTestCases(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/novel/evaluate/profiles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.profiles[1].profileName")
                        .value(RagEvaluationService.CHINESE_LIVE_PROFILE_NAME));
    }

    @Test
    void returnsEmptyReportWhenNoEvaluationHasRun() throws Exception {
        when(ragEvaluationService.getLastReport()).thenReturn(null);

        mockMvc.perform(get("/api/v1/novel/evaluate/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(0))
                .andExpect(jsonPath("$.reason").value("No evaluation has been run yet"));
    }
}
