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
            log.warn("测试数据集为空，无法评估");
            return EvaluationReport.empty("测试数据集为空，请检查 rag_eval_dataset.json");
        }

        log.info("开始 RAG 评估：novel_id={}, topK={}, 测试用例数={}", novelId, topK, testCases.size());

        List<Double> latencies = new ArrayList<>();
        int totalRelevant = 0;         // 所有 query 返回的"相关"结果总数
        int totalRetrieved = 0;        // 所有 query 返回的结果总数（topK * query数）
        double mrrSum = 0.0;
        int queriesWithRelevantResult = 0;
        List<QueryResult> detailResults = new ArrayList<>();

        for (TestCase tc : testCases) {
            long start = System.currentTimeMillis();

            // 执行检索
            List<Map<String, Object>> results = milvusSearchService.searchSegments(novelId, tc.getQuery(), topK);

            long elapsed = System.currentTimeMillis() - start;
            latencies.add((double) elapsed);

            // 分析结果
            Set<String> matchedKeywords = new HashSet<>();
            List<ResultItem> resultItems = new ArrayList<>();
            int firstRelevantRank = -1;

            for (int rank = 0; rank < results.size(); rank++) {
                Map<String, Object> item = results.get(rank);
                // 判断是否相关（内容包含任一期望关键词）
                String content = (String) item.getOrDefault("content", "");
                boolean isRelevant = false;
                Set<String> hitKeywords = new HashSet<>();
                for (String kw : tc.getExpectedKeywords()) {
                    if (content.toLowerCase().contains(kw.toLowerCase())) {
                        isRelevant = true;
                        hitKeywords.add(kw);
                        matchedKeywords.add(kw);
                    }
                }
                if (isRelevant && firstRelevantRank == -1) {
                    firstRelevantRank = rank + 1; // 1-based rank
                }
                resultItems.add(new ResultItem(rank + 1, content, item.get("score"), isRelevant, hitKeywords));
            }

            // 该 query 的指标
            long relevantCount = resultItems.stream().filter(ResultItem::isRelevant).count();
            boolean hasRelevant = relevantCount > 0;
            if (hasRelevant) {
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

        // 聚合指标
        int queryCount = testCases.size();
        double recallAtK = (double) queriesWithRelevantResult / queryCount * 100;
        double precisionAtK = (double) totalRelevant / Math.max(totalRetrieved, 1) * 100;
        double mrr = mrrSum / Math.max(queryCount, 1);
        double avgLatency = latencies.stream().mapToDouble(d -> d).average().orElse(0);
        double p99Latency = computeP99(latencies);
        double minLatency = latencies.stream().mapToDouble(d -> d).min().orElse(0);
        double maxLatency = latencies.stream().mapToDouble(d -> d).max().orElse(0);

        // 关键词覆盖率：所有 query 中匹配到的关键词占全部关键词的比例
        long totalKeywords = testCases.stream()
                .mapToLong(tc -> tc.getExpectedKeywords().size())
                .sum();
        long totalMatchedKeywords = detailResults.stream()
                .mapToLong(qr -> qr.getMatchedKeywords().size())
                .sum();
        double keywordCoverage = (double) totalMatchedKeywords / Math.max(totalKeywords, 1) * 100;

        lastReport = new EvaluationReport(
                System.currentTimeMillis(),
                queryCount, topK, recallAtK, precisionAtK, mrr,
                avgLatency, p99Latency, minLatency, maxLatency,
                keywordCoverage, queriesWithRelevantResult, detailResults
        );

        log.info("RAG 评估完成：Recall@{}={}%, Precision@{}={}%, MRR={}, 平均延迟={}ms, P99={}ms",
                topK, String.format("%.1f", recallAtK),
                topK, String.format("%.1f", precisionAtK),
                String.format("%.3f", mrr),
                String.format("%.0f", avgLatency),
                String.format("%.0f", p99Latency));

        return lastReport;
    }

    /**
     * 获取最后一次评估报告
     */
    public EvaluationReport getLastReport() {
        return lastReport;
    }

    /**
     * 获取测试用例列表
     */
    public List<TestCase> getTestCases() {
        return testCases;
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
        private double recallAtK;       // Recall@K (%)
        private double precisionAtK;    // Precision@K (%)
        private double mrr;             // Mean Reciprocal Rank
        private double avgLatencyMs;
        private double p99LatencyMs;
        private double minLatencyMs;
        private double maxLatencyMs;
        private double keywordCoverage; // 关键词覆盖率 (%)
        private int queriesWithRelevantResult;
        private List<QueryResult> details;

        public EvaluationReport() {}

        public EvaluationReport(long timestamp, int queryCount, int topK,
                                double recallAtK, double precisionAtK, double mrr,
                                double avgLatencyMs, double p99LatencyMs,
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
            r.p99LatencyMs = 0;
            r.minLatencyMs = 0;
            r.maxLatencyMs = 0;
            r.keywordCoverage = 0;
            r.queriesWithRelevantResult = 0;
            r.details = Collections.emptyList();
            return r;
        }

        // getters
        public long getTimestamp() { return timestamp; }
        public int getQueryCount() { return queryCount; }
        public int getTopK() { return topK; }
        public double getRecallAtK() { return recallAtK; }
        public double getPrecisionAtK() { return precisionAtK; }
        public double getMrr() { return mrr; }
        public double getAvgLatencyMs() { return avgLatencyMs; }
        public double getP99LatencyMs() { return p99LatencyMs; }
        public double getMinLatencyMs() { return minLatencyMs; }
        public double getMaxLatencyMs() { return maxLatencyMs; }
        public double getKeywordCoverage() { return keywordCoverage; }
        public int getQueriesWithRelevantResult() { return queriesWithRelevantResult; }
        public List<QueryResult> getDetails() { return details; }
    }

    // =============================================
    // 内部类：单条 query 结果
    // =============================================

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