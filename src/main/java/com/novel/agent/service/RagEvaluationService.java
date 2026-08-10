package com.novel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * RAG 效果评估服务
 * <p>
 * 用于量化评估 Milvus 向量检索的召回率、准确率、延迟等指标。
 * 测试数据集：src/main/resources/rag_eval_dataset.json
 * <p>
 * 评估指标：
 * - Recall@K：Top-K 中命中关键词的 query 占比
 * - Precision@K：Top-K 结果中命中关键词的比例
 * - MRR：首个相关结果的排位倒数均值
 * - 平均延迟 / P99 延迟
 * - 关键词覆盖率：匹配到的关键词占全部关键词的比例
 * <p>
 * 使用方式：POST /api/v1/novel/evaluate/segments?topK=5
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RagEvaluationService {

    private final MilvusSearchService milvusSearchService;
    private final ObjectMapper objectMapper;

    /** 测试数据集（运行时加载） */
    private List<TestCase> testCases = new ArrayList<>();

    /** 最后一次评估报告 */
    private EvaluationReport lastReport;

    /** ??????????? 5 ? */
    private final Deque<EvaluationSnapshot> reportHistory = new ArrayDeque<>();

    private static final int MAX_HISTORY_SIZE = 5;
    private static final String DEFAULT_PROFILE_NAME = "writing-default-v1";
    private static final String DEFAULT_DATASET_VERSION = "2026-08-09";

    @PostConstruct
    public void init() {
        try {
            ClassPathResource resource = new ClassPathResource("rag_eval_dataset.json");
            testCases = objectMapper.readValue(
                    resource.getInputStream(),
                    new TypeReference<List<TestCase>>() {
                    }
            );
            log.info("RAG 评估数据集加载完成，共 {} 条测试用例", testCases.size());
        } catch (Exception e) {
            log.warn("RAG 评估数据集加载失败（不影响项目启动）: {}", e.getMessage());
        }
    }

    /**
     * 运行评估
     *
     * @param novelId 小说ID（通常传 0 评估训练数据）
     * @param topK    Top-K 参数
     * @return 评估报告
     */
    public EvaluationReport evaluate(Long novelId, int topK) {
        if (testCases.isEmpty()) {
            log.warn("RAG evaluation skipped because the test dataset is empty");
            return EvaluationReport.empty("Test dataset is empty, please check rag_eval_dataset.json");
        }

        log.info("Starting RAG evaluation: novelId={}, topK={}, cases={}", novelId, topK, testCases.size());

        List<Double> latencies = new ArrayList<>();
        int totalRelevant = 0;
        int totalRetrieved = 0;
        double mrrSum = 0.0;
        int queriesWithRelevantResult = 0;
        long totalRetrievedContextChars = 0L;
        List<QueryResult> detailResults = new ArrayList<>();

        for (TestCase tc : testCases) {
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> results = milvusSearchService.searchSegments(novelId, tc.getQuery(), topK);
            long elapsed = System.currentTimeMillis() - startTime;
            latencies.add((double) elapsed);

            Set<String> matchedKeywords = new LinkedHashSet<>();
            List<ResultItem> resultItems = new ArrayList<>();
            int firstRelevantRank = -1;

            for (int rank = 0; rank < results.size(); rank++) {
                Map<String, Object> item = results.get(rank);
                String content = Objects.toString(item.getOrDefault("content", ""), "");
                boolean isRelevant = false;
                Set<String> hitKeywords = new LinkedHashSet<>();
                for (String keyword : tc.getExpectedKeywords()) {
                    if (content.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))) {
                        isRelevant = true;
                        hitKeywords.add(keyword);
                        matchedKeywords.add(keyword);
                    }
                }
                if (isRelevant && firstRelevantRank == -1) {
                    firstRelevantRank = rank + 1;
                }
                resultItems.add(new ResultItem(rank + 1, content, item.get("score"), isRelevant, hitKeywords));
            }

            long relevantCount = resultItems.stream().filter(ResultItem::isRelevant).count();
            totalRetrievedContextChars += resultItems.stream()
                    .mapToLong(item -> item.getContent() == null ? 0L : item.getContent().length())
                    .sum();
            if (relevantCount > 0) {
                queriesWithRelevantResult++;
                mrrSum += 1.0 / firstRelevantRank;
            }
            totalRelevant += relevantCount;
            totalRetrieved += results.size();

            detailResults.add(new QueryResult(
                    tc.getQuery(), tc.getCategory(), tc.getExpectedKeywords(),
                    results.size(), relevantCount, firstRelevantRank,
                    matchedKeywords, resultItems, elapsed
            ));
        }

        int queryCount = testCases.size();
        double recallAtK = safeRatio(queriesWithRelevantResult, queryCount);
        double precisionAtK = safeRatio(totalRelevant, totalRetrieved);
        double mrr = mrrSum / Math.max(queryCount, 1);
        double avgLatency = latencies.stream().mapToDouble(d -> d).average().orElse(0);
        double p95Latency = computeP95(latencies);
        double p99Latency = computeP99(latencies);
        double minLatency = latencies.stream().mapToDouble(d -> d).min().orElse(0);
        double maxLatency = latencies.stream().mapToDouble(d -> d).max().orElse(0);

        long totalKeywords = testCases.stream()
                .mapToLong(tc -> tc.getExpectedKeywords().size())
                .sum();
        long totalMatchedKeywords = detailResults.stream()
                .mapToLong(qr -> qr.getMatchedKeywords().size())
                .sum();
        double keywordCoverage = safeRatio(totalMatchedKeywords, totalKeywords);

        List<CategorySummary> categorySummaries = buildCategorySummaries(detailResults);
        EvaluationSnapshot previousSnapshot = reportHistory.peekLast();
        EvaluationSnapshot currentSnapshot = new EvaluationSnapshot(
                System.currentTimeMillis(),
                queryCount,
                topK,
                recallAtK,
                precisionAtK,
                mrr,
                avgLatency,
                p95Latency,
                p99Latency,
                keywordCoverage,
                queriesWithRelevantResult
        );
        currentSnapshot.setProfileName(DEFAULT_PROFILE_NAME);
        currentSnapshot.setDatasetVersion(DEFAULT_DATASET_VERSION);
        EvaluationComparison comparison = buildComparison(previousSnapshot, currentSnapshot);

        lastReport = new EvaluationReport(
                currentSnapshot.getTimestamp(),
                queryCount, topK, recallAtK, precisionAtK, mrr,
                avgLatency, p95Latency, p99Latency, minLatency, maxLatency,
                keywordCoverage, queriesWithRelevantResult, detailResults
        );
        lastReport.setProfileName(DEFAULT_PROFILE_NAME);
        lastReport.setDatasetVersion(DEFAULT_DATASET_VERSION);
        lastReport.setCategorySummaries(categorySummaries);
        lastReport.setComparison(comparison);
        double avgRetrievedContextChars = queryCount <= 0
                ? 0D
                : (double) totalRetrievedContextChars / queryCount;
        lastReport.setAvgRetrievedContextChars(avgRetrievedContextChars);
        lastReport.setAvgRetrievedContextTokens(Math.ceil(avgRetrievedContextChars / 4.0));

        addHistorySnapshot(currentSnapshot);
        lastReport.setHistory(new ArrayList<>(reportHistory));

        if (comparison != null) {
            log.info("RAG evaluation completed: Recall@{}={}%, Precision@{}={}%, MRR={}, Avg={}ms, P99={}ms, vs previous deltaRecall={}pp, deltaMRR={}",
                    topK, String.format(Locale.ROOT, "%.1f", recallAtK),
                    topK, String.format(Locale.ROOT, "%.1f", precisionAtK),
                    String.format(Locale.ROOT, "%.3f", mrr),
                    String.format(Locale.ROOT, "%.0f", avgLatency),
                    String.format(Locale.ROOT, "%.0f", p99Latency),
                    String.format(Locale.ROOT, "%.1f", comparison.getRecallAtKDelta()),
                    String.format(Locale.ROOT, "%.3f", comparison.getMrrDelta()));
        } else {
            log.info("RAG evaluation completed: Recall@{}={}%, Precision@{}={}%, MRR={}, Avg={}ms, P99={}ms",
                    topK, String.format(Locale.ROOT, "%.1f", recallAtK),
                    topK, String.format(Locale.ROOT, "%.1f", precisionAtK),
                    String.format(Locale.ROOT, "%.3f", mrr),
                    String.format(Locale.ROOT, "%.0f", avgLatency),
                    String.format(Locale.ROOT, "%.0f", p99Latency));
        }

        return lastReport;
    }

    public EvaluationReport getLastReport() {
        return lastReport;
    }

    /**
     * 获取测试用例列表
     */
    public List<TestCase> getTestCases() {
        return testCases;
    }

    private double computeP95(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(idx, 0));
    }

    private double computeP99(List<Double> values) {
        if (values.isEmpty()) return 0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int idx = (int) Math.ceil(0.99 * sorted.size()) - 1;
        return sorted.get(Math.max(idx, 0));
    }

    // =============================================
    // 内部类：测试用例
    // =============================================

    private double safeRatio(long numerator, long denominator) {
        if (denominator <= 0) {
            return 0D;
        }
        return (double) numerator / denominator * 100D;
    }

    private List<CategorySummary> buildCategorySummaries(List<QueryResult> detailResults) {
        if (detailResults.isEmpty()) {
            return List.of();
        }

        Map<String, List<QueryResult>> grouped = detailResults.stream()
                .collect(Collectors.groupingBy(
                        result -> normalizeCategory(result.getCategory()),
                        LinkedHashMap::new,
                        Collectors.toList()));

        return grouped.entrySet().stream()
                .map(entry -> buildCategorySummary(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(CategorySummary::getQueryCount).reversed()
                        .thenComparing(CategorySummary::getCategory))
                .collect(Collectors.toList());
    }

    private CategorySummary buildCategorySummary(String category, List<QueryResult> results) {
        int queryCount = results.size();
        long queriesWithRelevantResult = results.stream().filter(result -> result.getRelevantCount() > 0).count();
        long totalRelevant = results.stream().mapToLong(QueryResult::getRelevantCount).sum();
        long totalRetrieved = results.stream().mapToInt(QueryResult::getResultCount).sum();
        double reciprocalRankSum = results.stream()
                .mapToDouble(result -> result.getFirstRelevantRank() > 0 ? 1D / result.getFirstRelevantRank() : 0D)
                .sum();
        double avgLatency = results.stream().mapToLong(QueryResult::getLatencyMs).average().orElse(0D);
        double p95Latency = computeP95(results.stream().map(result -> (double) result.getLatencyMs()).collect(Collectors.toList()));
        double p99Latency = computeP99(results.stream().map(result -> (double) result.getLatencyMs()).collect(Collectors.toList()));
        long totalKeywords = results.stream().mapToLong(result -> result.getExpectedKeywords().size()).sum();
        long matchedKeywords = results.stream().mapToLong(result -> result.getMatchedKeywords().size()).sum();

        return new CategorySummary(
                category,
                queryCount,
                (int) queriesWithRelevantResult,
                safeRatio(queriesWithRelevantResult, queryCount),
                safeRatio(totalRelevant, totalRetrieved),
                reciprocalRankSum / Math.max(queryCount, 1),
                avgLatency,
                p95Latency,
                p99Latency,
                safeRatio(matchedKeywords, totalKeywords)
        );
    }

    private EvaluationComparison buildComparison(EvaluationSnapshot previous, EvaluationSnapshot current) {
        if (previous == null || current == null) {
            return null;
        }

        return new EvaluationComparison(
                previous.getTimestamp(),
                previous.getTopK(),
                current.getTopK(),
                current.getQueryCount() - previous.getQueryCount(),
                current.getQueriesWithRelevantResult() - previous.getQueriesWithRelevantResult(),
                round(current.getRecallAtK() - previous.getRecallAtK()),
                round(current.getPrecisionAtK() - previous.getPrecisionAtK()),
                round(current.getMrr() - previous.getMrr()),
                round(current.getAvgLatencyMs() - previous.getAvgLatencyMs()),
                round(current.getP95LatencyMs() - previous.getP95LatencyMs()),
                round(current.getP99LatencyMs() - previous.getP99LatencyMs()),
                round(current.getKeywordCoverage() - previous.getKeywordCoverage())
        );
    }

    private void addHistorySnapshot(EvaluationSnapshot snapshot) {
        reportHistory.addLast(snapshot);
        while (reportHistory.size() > MAX_HISTORY_SIZE) {
            reportHistory.removeFirst();
        }
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "uncategorized";
        }
        return category.trim();
    }

    private double round(double value) {
        return Math.round(value * 1000D) / 1000D;
    }

    public static class TestCase {
        private String query;
        private List<String> expectedKeywords;
        private String category;

        public TestCase() {}

        public String getQuery() { return query; }
        public void setQuery(String query) { this.query = query; }
        public List<String> getExpectedKeywords() { return expectedKeywords; }
        public void setExpectedKeywords(List<String> expectedKeywords) { this.expectedKeywords = expectedKeywords; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
    }

    // =============================================
    // 内部类：评估报告
    // =============================================

    public static class EvaluationReport {
        private long timestamp;
        private int queryCount;
        private int topK;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double avgLatencyMs;
        private double p95LatencyMs;
        private double p99LatencyMs;
        private double minLatencyMs;
        private double maxLatencyMs;
        private double keywordCoverage;
        private int queriesWithRelevantResult;
        private double avgRetrievedContextChars;
        private double avgRetrievedContextTokens;
        private List<QueryResult> details;
        private List<CategorySummary> categorySummaries;
        private EvaluationComparison comparison;
        private List<EvaluationSnapshot> history;
        private String profileName;
        private String datasetVersion;

        public EvaluationReport() {}

        public EvaluationReport(long timestamp, int queryCount, int topK,
                                double recallAtK, double precisionAtK, double mrr,
                                double avgLatencyMs, double p95LatencyMs, double p99LatencyMs,
                                double minLatencyMs, double maxLatencyMs,
                                double keywordCoverage, int queriesWithRelevantResult,
                                List<QueryResult> details) {
            this.timestamp = timestamp;
            this.queryCount = queryCount;
            this.topK = topK;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.mrr = mrr;
            this.avgLatencyMs = avgLatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.minLatencyMs = minLatencyMs;
            this.maxLatencyMs = maxLatencyMs;
            this.keywordCoverage = keywordCoverage;
            this.queriesWithRelevantResult = queriesWithRelevantResult;
            this.details = details;
        }

        public static EvaluationReport empty(String reason) {
            EvaluationReport r = new EvaluationReport();
            r.timestamp = System.currentTimeMillis();
            r.queryCount = 0;
            r.topK = 0;
            r.recallAtK = 0;
            r.precisionAtK = 0;
            r.mrr = 0;
            r.avgLatencyMs = 0;
            r.p95LatencyMs = 0;
            r.p99LatencyMs = 0;
            r.minLatencyMs = 0;
            r.maxLatencyMs = 0;
            r.keywordCoverage = 0;
            r.queriesWithRelevantResult = 0;
            r.details = Collections.emptyList();
            r.categorySummaries = Collections.emptyList();
            r.comparison = null;
            r.history = Collections.emptyList();
            r.profileName = DEFAULT_PROFILE_NAME;
            r.datasetVersion = DEFAULT_DATASET_VERSION;
            return r;
        }

        public long getTimestamp() { return timestamp; }
        public int getQueryCount() { return queryCount; }
        public int getTopK() { return topK; }
        public double getRecallAtK() { return recallAtK; }
        public double getPrecisionAtK() { return precisionAtK; }
        public double getMrr() { return mrr; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public double getP95LatencyMs() { return p95LatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public double getMinLatencyMs() { return minLatencyMs; }
        public double getMaxLatencyMs() { return maxLatencyMs; }
        public double getKeywordCoverage() { return keywordCoverage; }
        public int getQueriesWithRelevantResult() { return queriesWithRelevantResult; }
        public double getAvgRetrievedContextChars() { return avgRetrievedContextChars; }
        public double getAvgRetrievedContextTokens() { return avgRetrievedContextTokens; }
        public List<QueryResult> getDetails() { return details; }
        public List<CategorySummary> getCategorySummaries() { return categorySummaries; }
        public EvaluationComparison getComparison() { return comparison; }
        public List<EvaluationSnapshot> getHistory() { return history; }
        public String getProfileName() { return profileName; }
        public String getDatasetVersion() { return datasetVersion; }

        public void setCategorySummaries(List<CategorySummary> categorySummaries) {
            this.categorySummaries = categorySummaries;
        }

        public void setComparison(EvaluationComparison comparison) {
            this.comparison = comparison;
        }

        public void setHistory(List<EvaluationSnapshot> history) {
            this.history = history;
        }

        public void setProfileName(String profileName) {
            this.profileName = profileName;
        }

        public void setDatasetVersion(String datasetVersion) {
            this.datasetVersion = datasetVersion;
        }

        public void setAvgRetrievedContextChars(double avgRetrievedContextChars) {
            this.avgRetrievedContextChars = avgRetrievedContextChars;
        }

        public void setAvgRetrievedContextTokens(double avgRetrievedContextTokens) {
            this.avgRetrievedContextTokens = avgRetrievedContextTokens;
        }
    }

    public static class CategorySummary {
        private String category;
        private int queryCount;
        private int queriesWithRelevantResult;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double avgLatencyMs;
        private double p95LatencyMs;
        private double p99LatencyMs;
        private double keywordCoverage;

        public CategorySummary() {}

        public CategorySummary(String category, int queryCount, int queriesWithRelevantResult,
                               double recallAtK, double precisionAtK, double mrr,
                               double avgLatencyMs, double p95LatencyMs, double p99LatencyMs, double keywordCoverage) {
            this.category = category;
            this.queryCount = queryCount;
            this.queriesWithRelevantResult = queriesWithRelevantResult;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.mrr = mrr;
            this.avgLatencyMs = avgLatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.keywordCoverage = keywordCoverage;
        }

        public String getCategory() { return category; }
        public int getQueryCount() { return queryCount; }
        public int getQueriesWithRelevantResult() { return queriesWithRelevantResult; }
        public double getRecallAtK() { return recallAtK; }
        public double getPrecisionAtK() { return precisionAtK; }
        public double getMrr() { return mrr; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public double getP95LatencyMs() { return p95LatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public double getKeywordCoverage() { return keywordCoverage; }
    }

    public static class EvaluationSnapshot {
        private long timestamp;
        private int queryCount;
        private int topK;
        private double recallAtK;
        private double precisionAtK;
        private double mrr;
        private double avgLatencyMs;
        private double p95LatencyMs;
        private double p99LatencyMs;
        private double keywordCoverage;
        private int queriesWithRelevantResult;
        private String profileName;
        private String datasetVersion;

        public EvaluationSnapshot() {}

        public EvaluationSnapshot(long timestamp, int queryCount, int topK,
                                  double recallAtK, double precisionAtK, double mrr,
                                  double avgLatencyMs, double p95LatencyMs, double p99LatencyMs,
                                  double keywordCoverage, int queriesWithRelevantResult) {
            this.timestamp = timestamp;
            this.queryCount = queryCount;
            this.topK = topK;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.mrr = mrr;
            this.avgLatencyMs = avgLatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.keywordCoverage = keywordCoverage;
            this.queriesWithRelevantResult = queriesWithRelevantResult;
        }

        public long getTimestamp() { return timestamp; }
        public int getQueryCount() { return queryCount; }
        public int getTopK() { return topK; }
        public double getRecallAtK() { return recallAtK; }
        public double getPrecisionAtK() { return precisionAtK; }
        public double getMrr() { return mrr; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public double getP95LatencyMs() { return p95LatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public double getKeywordCoverage() { return keywordCoverage; }
        public int getQueriesWithRelevantResult() { return queriesWithRelevantResult; }
        public String getProfileName() { return profileName; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setProfileName(String profileName) { this.profileName = profileName; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
    }

    public static class EvaluationComparison {
        private long baselineTimestamp;
        private int baselineTopK;
        private int currentTopK;
        private int queryCountDelta;
        private int queriesWithRelevantResultDelta;
        private double recallAtKDelta;
        private double precisionAtKDelta;
        private double mrrDelta;
        private double avgLatencyMsDelta;
        private double p95LatencyMsDelta;
        private double p99LatencyMsDelta;
        private double keywordCoverageDelta;

        public EvaluationComparison() {}

        public EvaluationComparison(long baselineTimestamp, int baselineTopK, int currentTopK,
                                    int queryCountDelta, int queriesWithRelevantResultDelta,
                                    double recallAtKDelta, double precisionAtKDelta, double mrrDelta,
                                    double avgLatencyMsDelta, double p95LatencyMsDelta, double p99LatencyMsDelta, double keywordCoverageDelta) {
            this.baselineTimestamp = baselineTimestamp;
            this.baselineTopK = baselineTopK;
            this.currentTopK = currentTopK;
            this.queryCountDelta = queryCountDelta;
            this.queriesWithRelevantResultDelta = queriesWithRelevantResultDelta;
            this.recallAtKDelta = recallAtKDelta;
            this.precisionAtKDelta = precisionAtKDelta;
            this.mrrDelta = mrrDelta;
            this.avgLatencyMsDelta = avgLatencyMsDelta;
            this.p95LatencyMsDelta = p95LatencyMsDelta;
            this.p99LatencyMsDelta = p99LatencyMsDelta;
            this.keywordCoverageDelta = keywordCoverageDelta;
        }

        public long getBaselineTimestamp() { return baselineTimestamp; }
        public int getBaselineTopK() { return baselineTopK; }
        public int getCurrentTopK() { return currentTopK; }
        public int getQueryCountDelta() { return queryCountDelta; }
        public int getQueriesWithRelevantResultDelta() { return queriesWithRelevantResultDelta; }
        public double getRecallAtKDelta() { return recallAtKDelta; }
        public double getPrecisionAtKDelta() { return precisionAtKDelta; }
        public double getMrrDelta() { return mrrDelta; }
        public double getAvgLatencyMsDelta() { return avgLatencyMsDelta; }
        public double getP95LatencyMsDelta() { return p95LatencyMsDelta; }
        public double getP99LatencyMsDelta() { return p99LatencyMsDelta; }
        public double getKeywordCoverageDelta() { return keywordCoverageDelta; }
    }

    public static class QueryResult {
        private String query;
        private String category;
        private List<String> expectedKeywords;
        private int resultCount;
        private long relevantCount;
        private int firstRelevantRank;
        private Set<String> matchedKeywords;
        private List<ResultItem> items;
        private long latencyMs;

        public QueryResult() {}

        public QueryResult(String query, String category, List<String> expectedKeywords,
                           int resultCount, long relevantCount, int firstRelevantRank,
                           Set<String> matchedKeywords, List<ResultItem> items, long latencyMs) {
            this.query = query;
            this.category = category;
            this.expectedKeywords = expectedKeywords;
            this.resultCount = resultCount;
            this.relevantCount = relevantCount;
            this.firstRelevantRank = firstRelevantRank;
            this.matchedKeywords = matchedKeywords;
            this.items = items;
            this.latencyMs = latencyMs;
        }

        // getters
        public String getQuery() { return query; }
        public String getCategory() { return category; }
        public List<String> getExpectedKeywords() { return expectedKeywords; }
        public int getResultCount() { return resultCount; }
        public long getRelevantCount() { return relevantCount; }
        public int getFirstRelevantRank() { return firstRelevantRank; }
        public Set<String> getMatchedKeywords() { return matchedKeywords; }
        public List<ResultItem> getItems() { return items; }
        public long getLatencyMs() { return latencyMs; }
    }

    // =============================================
    // 内部类：单条结果项
    // =============================================

    public static class ResultItem {
        private int rank;
        private String content;
        private Object score;
        private boolean relevant;
        private Set<String> hitKeywords;

        public ResultItem() {}

        public ResultItem(int rank, String content, Object score, boolean relevant, Set<String> hitKeywords) {
            this.rank = rank;
            this.content = content;
            this.score = score;
            this.relevant = relevant;
            this.hitKeywords = hitKeywords;
        }

        // getters
        public int getRank() { return rank; }
        public String getContent() { return content; }
        public Object getScore() { return score; }
        public boolean isRelevant() { return relevant; }
        public Set<String> getHitKeywords() { return hitKeywords; }
    }
}
