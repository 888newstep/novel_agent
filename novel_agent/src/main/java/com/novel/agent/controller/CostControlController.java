package com.novel.agent.controller;

import com.novel.agent.service.TokenCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/cost")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class CostControlController {

    private final TokenCostService tokenCostService;

    @GetMapping("/summary")
    public ResponseEntity<TokenCostService.DashboardSummary> getSummary() {
        return ResponseEntity.ok(tokenCostService.getDashboardSummary());
    }

    @GetMapping("/records")
    public ResponseEntity<List<TokenCostService.UsageRecord>> getRecords(
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(tokenCostService.getRecentRecords(limit));
    }

    @GetMapping("/settings")
    public ResponseEntity<TokenCostService.SettingsSnapshot> getSettings() {
        return ResponseEntity.ok(tokenCostService.getSettingsSnapshot());
    }

    @PutMapping("/settings")
    public ResponseEntity<TokenCostService.SettingsSnapshot> updateSettings(
            @RequestBody TokenCostService.SettingsUpdateRequest request) {
        return ResponseEntity.ok(tokenCostService.updateSettings(request));
    }

    @DeleteMapping("/records")
    public ResponseEntity<Map<String, Object>> clearRecords() {
        tokenCostService.clearRecords();
        return ResponseEntity.ok(Map.of("success", true));
    }
}
