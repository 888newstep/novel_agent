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
    void returnsEmptyReportWhenNoEvaluationHasRun() throws Exception {
        when(ragEvaluationService.getLastReport()).thenReturn(null);

        mockMvc.perform(get("/api/v1/novel/evaluate/report"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queryCount").value(0));
    }
}

