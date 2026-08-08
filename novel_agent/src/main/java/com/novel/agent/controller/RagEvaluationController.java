package com.novel.agent.controller;

import com.novel.agent.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/novel/evaluate")
@RequiredArgsConstructor
public class RagEvaluationController {

    private final RagEvaluationService ragEvaluationService;

    @PostMapping("/segments")
    public ResponseEntity<RagEvaluationService.EvaluationReport> evaluateSegments(
            @RequestParam(defaultValue = "0") Long novelId,
            @RequestParam(defaultValue = "5") int topK) {

        log.info("RAG evaluation requested, novelId={}, topK={}", novelId, topK);
        if (ragEvaluationService.getTestCases().isEmpty()) {
            return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                    "Test dataset is empty"));
        }

        RagEvaluationService.EvaluationReport report = ragEvaluationService.evaluate(novelId, topK);
        return ResponseEntity.ok(report);
    }

    @GetMapping("/report")
    public ResponseEntity<RagEvaluationService.EvaluationReport> getLastReport() {
        log.info("RAG last report requested");
        RagEvaluationService.EvaluationReport report = ragEvaluationService.getLastReport();
        if (report == null) {
            return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                    "No evaluation has been run yet"));
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/test-cases")
    public ResponseEntity<?> getTestCases() {
        log.info("RAG test cases requested, count={}", ragEvaluationService.getTestCases().size());
        return ResponseEntity.ok(Map.of(
                "count", ragEvaluationService.getTestCases().size(),
                "cases", ragEvaluationService.getTestCases()
        ));
    }
}

