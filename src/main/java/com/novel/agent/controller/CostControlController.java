package com.novel.agent.controller;

import com.novel.agent.service.TokenCostService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/admin/cost")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CostControlController {

    private final TokenCostService tokenCostService;

    @GetMapping("/summary")
    public ResponseEntity<TokenCostService.DashboardSummary> getSummary() {
        log.info("Cost summary requested");
        return ResponseEntity.ok(tokenCostService.getDashboardSummary());
    }

    @GetMapping("/records")
    public ResponseEntity<List<TokenCostService.UsageRecord>> getRecords(
            @RequestParam(defaultValue = "100") int limit) {
        log.info("Cost records requested, limit={}", limit);
        return ResponseEntity.ok(tokenCostService.getRecentRecords(limit));
    }

    @GetMapping("/settings")
    public ResponseEntity<TokenCostService.SettingsSnapshot> getSettings() {
        log.info("Cost settings requested");
        return ResponseEntity.ok(tokenCostService.getSettingsSnapshot());
    }

    @PutMapping("/settings")
    public ResponseEntity<TokenCostService.SettingsSnapshot> updateSettings(
            @RequestBody TokenCostService.SettingsUpdateRequest request) {
        log.info("Cost settings update requested, strictMode={}, recentRecords={}",
                request.getStrictMode(), request.getRecentRecords());
        return ResponseEntity.ok(tokenCostService.updateSettings(request));
    }

    @DeleteMapping("/records")
    public ResponseEntity<Map<String, Object>> clearRecords() {
        log.info("Cost records clear requested");
        tokenCostService.clearRecords();
        return ResponseEntity.ok(Map.of("success", true));
    }
}

