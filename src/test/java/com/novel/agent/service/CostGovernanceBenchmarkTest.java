package com.novel.agent.service;

import com.novel.agent.config.AiProperties;
import com.novel.agent.exception.CostLimitExceededException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CostGovernanceBenchmarkTest {

    private static final int ATTEMPTS = 4;
    private static final String PROMPT = "?????";
    private static final String COMPLETION = "?????";

    @Test
    void comparesUngovernedAndGovernedWritingRequests() throws Exception {
        ScenarioSnapshot before = runScenario(false);
        ScenarioSnapshot after = runScenario(true);

        assertEquals(ATTEMPTS, before.acceptedRequests());
        assertEquals(0, before.blockedRequests());
        assertEquals(40, before.billableTokens());
        assertEquals(4.0, before.estimatedCostUsd(), 0.0001);

        assertEquals(2, after.acceptedRequests());
        assertEquals(2, after.blockedRequests());
        assertEquals(20, after.billableTokens());
        assertEquals(2.0, after.estimatedCostUsd(), 0.0001);

        double tokenReductionPct = reductionPercent(before.billableTokens(), after.billableTokens());
        double costReductionPct = reductionPercent(before.estimatedCostUsd(), after.estimatedCostUsd());
        assertEquals(50.0, tokenReductionPct, 0.0001);
        assertEquals(50.0, costReductionPct, 0.0001);

        String report = buildReport(before, after, tokenReductionPct, costReductionPct);
        writeReportIfRequested(report);
        System.out.println("COST_GOVERNANCE_BENCHMARK " + report.replaceAll("\\s+", " ").trim());
    }

    private ScenarioSnapshot runScenario(boolean strictMode) {
        AiProperties properties = createProperties(strictMode);
        TokenCostService service = new TokenCostService(properties);
        int acceptedRequests = 0;
        int blockedRequests = 0;

        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            try {
                TokenCostService.UsageReservation reservation = service.reserveChatRequest(
                        7L,
                        "fixture",
                        "writing-model",
                        "cost.benchmark",
                        PROMPT,
                        5
                );
                service.recordChatSuccess(reservation, COMPLETION, 5, 5);
                acceptedRequests++;
            } catch (CostLimitExceededException ex) {
                blockedRequests++;
            }
        }

        TokenCostService.UsageWindow window = service.getDashboardSummary().getToday();
        return new ScenarioSnapshot(
                strictMode,
                window.getRequestCount(),
                acceptedRequests,
                blockedRequests,
                window.getBillableTokens(),
                window.getEstimatedCostUsd()
        );
    }

    private AiProperties createProperties(boolean strictMode) {
        AiProperties properties = new AiProperties();
        AiProperties.CostControl costControl = properties.getCostControl();
        costControl.setEnabled(true);
        costControl.setStrictMode(strictMode);
        costControl.setRecentRecords(50);
        costControl.setMaxEstimatedTokensPerRequest(100);
        costControl.setDailyTokenBudget(20);
        costControl.setMonthlyTokenBudget(100);
        costControl.setDailyBudgetUsd(0);
        costControl.setMonthlyBudgetUsd(0);
        costControl.setPerNovelDailyTokenBudget(0);
        costControl.setPerModelDailyTokenBudget(0);

        // Synthetic pricing keeps the deterministic report readable; it is not vendor pricing.
        costControl.getPricing().setInputPerMillionTokens(100_000D);
        costControl.getPricing().setOutputPerMillionTokens(100_000D);
        return properties;
    }

    private double reductionPercent(double before, double after) {
        return Math.round((before - after) * 10_000D / before) / 100D;
    }

    private String buildReport(ScenarioSnapshot before,
                               ScenarioSnapshot after,
                               double tokenReductionPct,
                               double costReductionPct) {
        return String.format(Locale.ROOT, """
                {
                  "benchmarkId": "COST-GOVERNANCE-20260810",
                  "attempts": %d,
                  "before": %s,
                  "after": %s,
                  "delta": {
                    "billableTokenReductionPct": %.2f,
                    "estimatedCostReductionPct": %.2f,
                    "blockedRequestsAdded": %d
                  },
                  "pricingFixture": {
                    "inputPerMillionTokens": 100000,
                    "outputPerMillionTokens": 100000,
                    "synthetic": true
                  },
                  "caveat": "This benchmark validates governance behavior, not a production provider price."
                }
                """, ATTEMPTS, before.toJson(), after.toJson(), tokenReductionPct, costReductionPct,
                after.blockedRequests() - before.blockedRequests());
    }

    private void writeReportIfRequested(String report) throws Exception {
        String outputPath = System.getProperty("cost.benchmark.output");
        if (outputPath == null || outputPath.isBlank()) {
            return;
        }
        Path path = Paths.get(outputPath).toAbsolutePath();
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(path, report, StandardCharsets.UTF_8);
    }

    private record ScenarioSnapshot(boolean strictMode,
                                    int requestCount,
                                    int acceptedRequests,
                                    int blockedRequests,
                                    int billableTokens,
                                    double estimatedCostUsd) {

        private String toJson() {
            return String.format(Locale.ROOT, """
                    {
                      "strictMode": %s,
                      "requestCount": %d,
                      "acceptedRequests": %d,
                      "blockedRequests": %d,
                      "billableTokens": %d,
                      "estimatedCostUsd": %.4f
                    }
                    """, strictMode, requestCount, acceptedRequests, blockedRequests,
                    billableTokens, estimatedCostUsd);
        }
    }
}
