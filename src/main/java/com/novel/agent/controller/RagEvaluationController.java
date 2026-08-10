package com.novel.agent.controller;

import com.novel.agent.service.RagEvaluationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
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
            @RequestParam(defaultValue = "5") int topK,
            @RequestParam(defaultValue = RagEvaluationService.DEFAULT_PROFILE_NAME) String profile) {

        String normalizedProfile = normalizeProfile(profile);
        log.info("RAG evaluation requested, novelId={}, topK={}, profile={}",
                novelId, topK, normalizedProfile);

        if (RagEvaluationService.DEFAULT_PROFILE_NAME.equals(normalizedProfile)) {
            if (ragEvaluationService.getTestCases().isEmpty()) {
                return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                        normalizedProfile,
                        ragEvaluationService.getDatasetVersion(normalizedProfile),
                        "Test dataset is empty"));
            }
            return ResponseEntity.ok(ragEvaluationService.evaluate(novelId, topK));
        }

        return ResponseEntity.ok(ragEvaluationService.evaluate(novelId, topK, normalizedProfile));
    }

    @GetMapping("/report")
    public ResponseEntity<RagEvaluationService.EvaluationReport> getLastReport(
            @RequestParam(defaultValue = RagEvaluationService.DEFAULT_PROFILE_NAME) String profile) {
        String normalizedProfile = normalizeProfile(profile);
        log.info("RAG last report requested, profile={}", normalizedProfile);
        RagEvaluationService.EvaluationReport report =
                RagEvaluationService.DEFAULT_PROFILE_NAME.equals(normalizedProfile)
                        ? ragEvaluationService.getLastReport()
                        : ragEvaluationService.getLastReport(normalizedProfile);
        if (report == null) {
            return ResponseEntity.ok(RagEvaluationService.EvaluationReport.empty(
                    normalizedProfile,
                    ragEvaluationService.getDatasetVersion(normalizedProfile),
                    "No evaluation has been run yet"));
        }
        return ResponseEntity.ok(report);
    }

    @GetMapping("/test-cases")
    public ResponseEntity<?> getTestCases(
            @RequestParam(defaultValue = RagEvaluationService.DEFAULT_PROFILE_NAME) String profile) {
        String normalizedProfile = normalizeProfile(profile);
        List<RagEvaluationService.TestCase> cases =
                RagEvaluationService.DEFAULT_PROFILE_NAME.equals(normalizedProfile)
                        ? ragEvaluationService.getTestCases()
                        : ragEvaluationService.getTestCases(normalizedProfile);
        log.info("RAG test cases requested, profile={}, count={}", normalizedProfile, cases.size());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("profileName", normalizedProfile);
        response.put("datasetVersion", ragEvaluationService.getDatasetVersion(normalizedProfile));
        response.put("count", cases.size());
        response.put("cases", cases);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/profiles")
    public ResponseEntity<?> getProfiles() {
        List<Map<String, Object>> profiles = ragEvaluationService.getAvailableProfiles().stream()
                .map(profileName -> Map.<String, Object>of(
                        "profileName", profileName,
                        "datasetVersion", ragEvaluationService.getDatasetVersion(profileName),
                        "caseCount", ragEvaluationService.getTestCases(profileName).size()
                ))
                .toList();
        return ResponseEntity.ok(Map.of(
                "count", profiles.size(),
                "profiles", profiles
        ));
    }

    private String normalizeProfile(String profile) {
        return profile == null || profile.isBlank()
                ? RagEvaluationService.DEFAULT_PROFILE_NAME
                : profile.trim();
    }
}
