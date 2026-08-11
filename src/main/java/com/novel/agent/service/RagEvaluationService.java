package com.novel.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel.agent.entity.RagEvaluationSnapshot;
import com.novel.agent.repository.RagEvaluationSnapshotRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
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
public class RagEvaluationService {

    private final MilvusSearchService milvusSearchService;
    private final ObjectMapper objectMapper;
    private final RagEvaluationSnapshotRepository snapshotRepository;
    private final RagEvaluationMetrics ragEvaluationMetrics;

    /** 默认评测 profile，保持既有 API 和 CI fixture 的兼容性。 */
    public static final String DEFAULT_PROFILE_NAME = "writing-default-v1";

    /** 与云端中文生产语料对齐的评测 profile。 */
    public static final String CHINESE_LIVE_PROFILE_NAME = "writing-zh-live-v1";

    private static final int MAX_HISTORY_SIZE = 5;
    private static final int MAX_HISTORY_QUERY_LIMIT = 50;
    private static final String DEFAULT_DATASET_VERSION = "2026-08-09";
    private static final String CHINESE_LIVE_DATASET_VERSION = "2026-08-10";
    private static final Map<String, DatasetDefinition> DATASET_DEFINITIONS = createDatasetDefinitions();

    /** 默认数据集缓存，保留旧的无参访问方式。 */
    private List<TestCase> testCases = List.of();

    /** 按 profile 隔离数据集、历史和最近报告，避免跨语料比较。 */
    private final Map<String, List<TestCase>> datasets = new LinkedHashMap<>();
    private final Map<String, Deque<EvaluationSnapshot>> reportHistories = new LinkedHashMap<>();
    private final Map<String, EvaluationReport> lastReports = new LinkedHashMap<>();

    /** 保留无数据库单元测试和脚本场景的轻量构造方式。 */
    public RagEvaluationService(MilvusSearchService milvusSearchService, ObjectMapper objectMapper) {
        this(milvusSearchService, objectMapper, null, null);
    }

    public RagEvaluationService(MilvusSearchService milvusSearchService,
                                ObjectMapper objectMapper,
                                RagEvaluationSnapshotRepository snapshotRepository) {
        this(milvusSearchService, objectMapper, snapshotRepository, null);
    }

    @Autowired
    public RagEvaluationService(MilvusSearchService milvusSearchService,
                                ObjectMapper objectMapper,
                                RagEvaluationSnapshotRepository snapshotRepository,
                                RagEvaluationMetrics ragEvaluationMetrics) {
        this.milvusSearchService = milvusSearchService;
        this.objectMapper = objectMapper;
        this.snapshotRepository = snapshotRepository;
        this.ragEvaluationMetrics = ragEvaluationMetrics;
    }

    private static Map<String, DatasetDefinition> createDatasetDefinitions() {
        Map<String, DatasetDefinition> definitions = new LinkedHashMap<>();
        definitions.put(DEFAULT_PROFILE_NAME,
                new DatasetDefinition("rag_eval_dataset.json", DEFAULT_DATASET_VERSION));
        definitions.put(CHINESE_LIVE_PROFILE_NAME,
                new DatasetDefinition("rag_eval_dataset_zh.json", CHINESE_LIVE_DATASET_VERSION));
        return Collections.unmodifiableMap(definitions);
    }

    @PostConstruct
    public synchronized void init() {
        datasets.clear();
        reportHistories.clear();
        lastReports.clear();

        DATASET_DEFINITIONS.forEach((profileName, definition) -> {
            try {
                ClassPathResource resource = new ClassPathResource(definition.resourceName());
                List<TestCase> loadedCases;
                try (var inputStream = resource.getInputStream()) {
                    loadedCases = objectMapper.readValue(
                            inputStream,
                            new TypeReference<List<TestCase>>() {
                            }
                    );
                }
                datasets.put(profileName, loadedCases == null ? List.of() : List.copyOf(loadedCases));
                log.info("RAG 评估数据集加载完成，profile={}，version={}，cases={}",
                        profileName, definition.datasetVersion(), datasets.get(profileName).size());
            } catch (Exception e) {
                datasets.put(profileName, List.of());
                log.warn("RAG 评估数据集加载失败，profile={}，resource={}，reason={}",
                        profileName, definition.resourceName(), e.getMessage());
            }
        });

        testCases = getTestCases(DEFAULT_PROFILE_NAME);
        loadPersistedHistory();
    }

    /**
     * 运行评估
     *
     * @param novelId 小说ID（通常传 0 评估训练数据）
     * @param topK    Top-K 参数
     * @return 评估报告
     */
    public EvaluationReport evaluate(Long novelId, int topK) {
        return evaluate(novelId, topK, DEFAULT_PROFILE_NAME);
    }

    /**
     * 按指定 profile 运行评估。不同 profile 的历史独立维护，禁止把不同语料的指标直接做 delta。
     */
    public synchronized EvaluationReport evaluate(Long novelId, int topK, String profileName) {
        String normalizedProfile = normalizeProfile(profileName);
        Long normalizedNovelId = normalizeNovelId(novelId);
        DatasetDefinition definition = DATASET_DEFINITIONS.get(normalizedProfile);
        if (definition == null) {
            String reason = "Unknown evaluation profile: " + normalizedProfile
                    + "; available profiles: " + String.join(", ", getAvailableProfiles());
            log.warn("RAG evaluation skipped: {}", reason);
            if (ragEvaluationMetrics != null) {
                ragEvaluationMetrics.recordSkipped(normalizedProfile,
                        RagEvaluationMetrics.SkipReason.UNKNOWN_PROFILE);
            }
            return EvaluationReport.empty(normalizedProfile, null, reason);
        }

        List<TestCase> cases = datasets.getOrDefault(normalizedProfile, List.of());
        if (cases.isEmpty()) {
            String reason = "Test dataset is empty, please check " + definition.resourceName();
            log.warn("RAG evaluation skipped, profile={}, reason={}", normalizedProfile, reason);
            if (ragEvaluationMetrics != null) {
                ragEvaluationMetrics.recordSkipped(normalizedProfile,
                        RagEvaluationMetrics.SkipReason.EMPTY_DATASET);
            }
            return EvaluationReport.empty(normalizedProfile, definition.datasetVersion(), reason);
        }
        if (topK <= 0) {
            String reason = "topK must be greater than 0";
            log.warn("RAG evaluation skipped, profile={}, reason={}", normalizedProfile, reason);
            if (ragEvaluationMetrics != null) {
                ragEvaluationMetrics.recordSkipped(normalizedProfile,
                        RagEvaluationMetrics.SkipReason.INVALID_TOP_K);
            }
            return EvaluationReport.empty(normalizedProfile, definition.datasetVersion(), reason);
        }

        log.info("Starting RAG evaluation: novelId={}, topK={}, profile={}, cases={}",
                normalizedNovelId, topK, normalizedProfile, cases.size());

        List<Double> latencies = new ArrayList<>();
        int totalRelevant = 0;
        int totalRetrieved = 0;
        double mrrSum = 0.0;
        int queriesWithRelevantResult = 0;
        long totalRetrievedContextChars = 0L;
        List<QueryResult> detailResults = new ArrayList<>();

        for (TestCase tc : cases) {
            String query = tc == null || tc.getQuery() == null ? "" : tc.getQuery();
            List<String> expectedKeywords = safeKeywords(tc);
            long startTime = System.currentTimeMillis();
            List<Map<String, Object>> results;
            try {
                results = milvusSearchService.searchSegments(normalizedNovelId, query, topK);
                results = results == null ? List.of() : results;
            } catch (RuntimeException exception) {
                log.warn("RAG evaluation query failed, profile={}, query={}, reason={}",
                        normalizedProfile, query, exception.getMessage());
                results = List.of();
            }
            long elapsed = System.currentTimeMillis() - startTime;
            latencies.add((double) elapsed);
            if (ragEvaluationMetrics != null) {
                ragEvaluationMetrics.recordQueryLatency(normalizedProfile, elapsed);
            }

            Set<String> matchedKeywords = new LinkedHashSet<>();
            List<ResultItem> resultItems = new ArrayList<>();
            int firstRelevantRank = -1;

            for (int rank = 0; rank < results.size(); rank++) {
                Map<String, Object> item = results.get(rank) == null ? Map.of() : results.get(rank);
                String content = Objects.toString(item.getOrDefault("content", ""), "");
                boolean isRelevant = false;
                Set<String> hitKeywords = new LinkedHashSet<>();
                for (String keyword : expectedKeywords) {
                    if (keyword == null || keyword.isBlank()) {
                        continue;
                    }
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
                    query, tc == null ? null : tc.getCategory(), expectedKeywords,
                    results.size(), relevantCount, firstRelevantRank,
                    matchedKeywords, resultItems, elapsed
            ));
        }

        int queryCount = cases.size();
        double recallAtK = safeRatio(queriesWithRelevantResult, queryCount);
        double precisionAtK = safeRatio(totalRelevant, totalRetrieved);
        double mrr = mrrSum / Math.max(queryCount, 1);
        double avgLatency = latencies.stream().mapToDouble(d -> d).average().orElse(0);
        double p95Latency = computeP95(latencies);
        double p99Latency = computeP99(latencies);
        double minLatency = latencies.stream().mapToDouble(d -> d).min().orElse(0);
        double maxLatency = latencies.stream().mapToDouble(d -> d).max().orElse(0);

        long totalKeywords = cases.stream()
                .mapToLong(tc -> safeKeywords(tc).size())
                .sum();
        long totalMatchedKeywords = detailResults.stream()
                .mapToLong(qr -> qr.getMatchedKeywords().size())
                .sum();
        double keywordCoverage = safeRatio(totalMatchedKeywords, totalKeywords);

        String historyKey = historyKey(normalizedProfile, normalizedNovelId);
        Deque<EvaluationSnapshot> reportHistory = reportHistories.computeIfAbsent(
                historyKey, ignored -> new ArrayDeque<>());
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
        currentSnapshot.setProfileName(normalizedProfile);
        currentSnapshot.setDatasetVersion(definition.datasetVersion());
        currentSnapshot.setNovelId(normalizedNovelId);
        currentSnapshot.setMinLatencyMs(minLatency);
        currentSnapshot.setMaxLatencyMs(maxLatency);
        currentSnapshot.setAvgContextChars(queryCount <= 0
                ? 0D : (double) totalRetrievedContextChars / queryCount);
        currentSnapshot.setAvgContextTokens(Math.ceil(currentSnapshot.getAvgContextChars() / 4D));
        EvaluationComparison comparison = buildComparison(previousSnapshot, currentSnapshot);

        EvaluationReport report = new EvaluationReport(
                currentSnapshot.getTimestamp(),
                queryCount, topK, recallAtK, precisionAtK, mrr,
                avgLatency, p95Latency, p99Latency, minLatency, maxLatency,
                keywordCoverage, queriesWithRelevantResult, detailResults
        );
        report.setProfileName(normalizedProfile);
        report.setDatasetVersion(definition.datasetVersion());
        report.setReason(null);
        report.setCategorySummaries(buildCategorySummaries(detailResults));
        report.setComparison(comparison);
        double avgRetrievedContextChars = queryCount <= 0
                ? 0D
                : (double) totalRetrievedContextChars / queryCount;
        report.setAvgRetrievedContextChars(avgRetrievedContextChars);
        report.setAvgRetrievedContextTokens(Math.ceil(avgRetrievedContextChars / 4.0));

        addHistorySnapshot(historyKey, currentSnapshot);
        report.setHistory(new ArrayList<>(reportHistory));
        lastReports.put(historyKey, report);
        persistSnapshot(normalizedNovelId, report);
        if (ragEvaluationMetrics != null) {
            ragEvaluationMetrics.recordEvaluation(
                    normalizedProfile,
                    topK,
                    report.getQueryCount(),
                    report.getRecallAtK(),
                    report.getPrecisionAtK(),
                    report.getMrr(),
                    report.getKeywordCoverage(),
                    report.getAvgLatencyMs(),
                    report.getP95LatencyMs(),
                    report.getP99LatencyMs(),
                    report.getTimestamp()
            );
        }

        if (comparison != null) {
            log.info("RAG evaluation completed: profile={}, Recall@{}={}%, Precision@{}={}%, MRR={}, Avg={}ms, P99={}ms, vs previous deltaRecall={}pp, deltaMRR={}",
                    normalizedProfile, topK, String.format(Locale.ROOT, "%.1f", recallAtK),
                    topK, String.format(Locale.ROOT, "%.1f", precisionAtK),
                    String.format(Locale.ROOT, "%.3f", mrr),
                    String.format(Locale.ROOT, "%.0f", avgLatency),
                    String.format(Locale.ROOT, "%.0f", p99Latency),
                    String.format(Locale.ROOT, "%.1f", comparison.getRecallAtKDelta()),
                    String.format(Locale.ROOT, "%.3f", comparison.getMrrDelta()));
        } else {
            log.info("RAG evaluation completed: profile={}, Recall@{}={}%, Precision@{}={}%, MRR={}, Avg={}ms, P99={}ms",
                    normalizedProfile, topK, String.format(Locale.ROOT, "%.1f", recallAtK),
                    topK, String.format(Locale.ROOT, "%.1f", precisionAtK),
                    String.format(Locale.ROOT, "%.3f", mrr),
                    String.format(Locale.ROOT, "%.0f", avgLatency),
                    String.format(Locale.ROOT, "%.0f", p99Latency));
        }

        return report;
    }

    public synchronized EvaluationReport getLastReport() {
        return getLastReport(DEFAULT_PROFILE_NAME);
    }

    public synchronized EvaluationReport getLastReport(String profileName) {
        String normalizedProfile = normalizeProfile(profileName);
        EvaluationReport memoryReport = lastReports.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(normalizedProfile + "|"))
                .map(Map.Entry::getValue)
                .max(Comparator.comparingLong(EvaluationReport::getTimestamp))
                .orElse(null);
        if (memoryReport != null || snapshotRepository == null) {
            return memoryReport;
        }

        try {
            return snapshotRepository.findFirstByProfileNameOrderByEvaluatedAtDesc(normalizedProfile)
                    .map(snapshot -> toAggregateReport(
                            toEvaluationSnapshot(snapshot),
                            List.of(toEvaluationSnapshot(snapshot))))
                    .orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Failed to load latest persisted RAG report by profile, profile={}, reason={}",
                    normalizedProfile, exception.getMessage());
            return null;
        }
    }

    public synchronized EvaluationReport getLastReport(Long novelId, String profileName) {
        String normalizedProfile = normalizeProfile(profileName);
        Long normalizedNovelId = normalizeNovelId(novelId);
        String key = historyKey(normalizedProfile, normalizedNovelId);
        EvaluationReport report = lastReports.get(key);
        if (report != null) {
            return report;
        }

        if (snapshotRepository == null) {
            return null;
        }

        try {
            return snapshotRepository.findFirstByProfileNameAndNovelIdOrderByEvaluatedAtDesc(
                            normalizedProfile, normalizedNovelId)
                    .map(snapshot -> {
                        EvaluationSnapshot latest = toEvaluationSnapshot(snapshot);
                        addHistorySnapshot(key, latest);
                        EvaluationReport restored = toAggregateReport(
                                latest,
                                new ArrayList<>(reportHistories.get(key)));
                        lastReports.put(key, restored);
                        return restored;
                    })
                    .orElse(null);
        } catch (RuntimeException exception) {
            log.warn("Failed to load last persisted RAG report, profile={}, novelId={}, reason={}",
                    normalizedProfile, normalizedNovelId, exception.getMessage());
            return null;
        }
    }

    /**
     * 获取持久化的聚合评测历史。
     * 返回值不包含查询详情，避免把小说原文或测试 query 暴露给趋势面板。
     */
    public synchronized List<EvaluationSnapshot> getHistory(Long novelId, String profileName, int limit) {
        String normalizedProfile = normalizeProfile(profileName);
        Long normalizedNovelId = normalizeNovelId(novelId);
        int normalizedLimit = Math.max(1, Math.min(limit, MAX_HISTORY_QUERY_LIMIT));
        String key = historyKey(normalizedProfile, normalizedNovelId);

        if (snapshotRepository != null) {
            try {
                List<EvaluationSnapshot> persistedHistory = snapshotRepository
                        .findByProfileNameAndNovelIdOrderByEvaluatedAtDesc(
                                normalizedProfile,
                                normalizedNovelId,
                                PageRequest.of(0, normalizedLimit))
                        .stream()
                        .map(this::toEvaluationSnapshot)
                        .toList();
                if (!persistedHistory.isEmpty()) {
                    return persistedHistory;
                }
            } catch (RuntimeException exception) {
                log.warn("Failed to load persisted RAG history, profile={}, novelId={}, reason={}",
                        normalizedProfile, normalizedNovelId, exception.getMessage());
            }
        }

        Deque<EvaluationSnapshot> memoryHistory = reportHistories.getOrDefault(key, new ArrayDeque<>());
        return memoryHistory.stream()
                .sorted(Comparator.comparingLong(EvaluationSnapshot::getTimestamp).reversed())
                .limit(normalizedLimit)
                .toList();
    }

    /**
     * 获取默认 profile 的测试用例列表。
     */
    public synchronized List<TestCase> getTestCases() {
        return getTestCases(DEFAULT_PROFILE_NAME);
    }

    public synchronized List<TestCase> getTestCases(String profileName) {
        return List.copyOf(datasets.getOrDefault(normalizeProfile(profileName), List.of()));
    }

    public List<String> getAvailableProfiles() {
        return List.copyOf(DATASET_DEFINITIONS.keySet());
    }

    public String getDatasetVersion(String profileName) {
        DatasetDefinition definition = DATASET_DEFINITIONS.get(normalizeProfile(profileName));
        return definition == null ? null : definition.datasetVersion();
    }

    private void loadPersistedHistory() {
        if (snapshotRepository == null) {
            return;
        }

        try {
            List<RagEvaluationSnapshot> persistedSnapshots = snapshotRepository
                    .findAllByOrderByEvaluatedAtDesc(PageRequest.of(0, MAX_HISTORY_QUERY_LIMIT * 4));
            Map<String, List<RagEvaluationSnapshot>> grouped = persistedSnapshots.stream()
                    .collect(Collectors.groupingBy(
                            snapshot -> historyKey(snapshot.getProfileName(), snapshot.getNovelId()),
                            LinkedHashMap::new,
                            Collectors.toList()));

            grouped.forEach((key, snapshots) -> {
                List<RagEvaluationSnapshot> chronological = snapshots.stream()
                        .sorted(Comparator.comparingLong(RagEvaluationSnapshot::getEvaluatedAt))
                        .toList();
                Deque<EvaluationSnapshot> history = reportHistories.computeIfAbsent(
                        key, ignored -> new ArrayDeque<>());
                chronological.stream()
                        .skip(Math.max(0, chronological.size() - MAX_HISTORY_SIZE))
                        .map(this::toEvaluationSnapshot)
                        .forEach(history::addLast);

                if (!history.isEmpty()) {
                    EvaluationSnapshot latest = history.peekLast();
                    lastReports.put(key, toAggregateReport(latest, new ArrayList<>(history)));
                    if (ragEvaluationMetrics != null) {
                        ragEvaluationMetrics.restoreLatest(
                                latest.getProfileName(),
                                latest.getTopK(),
                                latest.getQueryCount(),
                                latest.getRecallAtK(),
                                latest.getPrecisionAtK(),
                                latest.getMrr(),
                                latest.getKeywordCoverage(),
                                latest.getAvgLatencyMs(),
                                latest.getP95LatencyMs(),
                                latest.getP99LatencyMs(),
                                latest.getTimestamp()
                        );
                    }
                }
            });
            log.info("Loaded persisted RAG evaluation history, snapshotCount={}, seriesCount={}",
                    persistedSnapshots.size(), grouped.size());
        } catch (RuntimeException exception) {
            log.warn("Failed to load persisted RAG evaluation history; memory history remains available, reason={}",
                    exception.getMessage());
        }
    }

    private void persistSnapshot(Long novelId, EvaluationReport report) {
        if (snapshotRepository == null || report == null) {
            return;
        }

        try {
            snapshotRepository.save(RagEvaluationSnapshot.builder()
                    .profileName(report.getProfileName())
                    .datasetVersion(report.getDatasetVersion())
                    .novelId(normalizeNovelId(novelId))
                    .topK(report.getTopK())
                    .queryCount(report.getQueryCount())
                    .queriesWithRelevantResult(report.getQueriesWithRelevantResult())
                    .recallAtK(report.getRecallAtK())
                    .precisionAtK(report.getPrecisionAtK())
                    .mrr(report.getMrr())
                    .avgLatencyMs(report.getAvgLatencyMs())
                    .p95LatencyMs(report.getP95LatencyMs())
                    .p99LatencyMs(report.getP99LatencyMs())
                    .minLatencyMs(report.getMinLatencyMs())
                    .maxLatencyMs(report.getMaxLatencyMs())
                    .keywordCoverage(report.getKeywordCoverage())
                    .avgContextChars(report.getAvgRetrievedContextChars())
                    .avgContextTokens(report.getAvgRetrievedContextTokens())
                    .evaluatedAt(report.getTimestamp())
                    .build());
        } catch (RuntimeException exception) {
            log.warn("Failed to persist RAG evaluation snapshot, profile={}, novelId={}, reason={}",
                    report.getProfileName(), normalizeNovelId(novelId), exception.getMessage());
            if (ragEvaluationMetrics != null) {
                ragEvaluationMetrics.recordPersistenceFailure(report.getProfileName());
            }
        }
    }

    private EvaluationSnapshot toEvaluationSnapshot(RagEvaluationSnapshot snapshot) {
        EvaluationSnapshot result = new EvaluationSnapshot(
                snapshot.getEvaluatedAt(),
                snapshot.getQueryCount(),
                snapshot.getTopK(),
                snapshot.getRecallAtK(),
                snapshot.getPrecisionAtK(),
                snapshot.getMrr(),
                snapshot.getAvgLatencyMs(),
                snapshot.getP95LatencyMs(),
                snapshot.getP99LatencyMs(),
                snapshot.getKeywordCoverage(),
                snapshot.getQueriesWithRelevantResult());
        result.setProfileName(snapshot.getProfileName());
        result.setDatasetVersion(snapshot.getDatasetVersion());
        result.setNovelId(snapshot.getNovelId());
        result.setMinLatencyMs(snapshot.getMinLatencyMs());
        result.setMaxLatencyMs(snapshot.getMaxLatencyMs());
        result.setAvgContextChars(snapshot.getAvgContextChars());
        result.setAvgContextTokens(snapshot.getAvgContextTokens());
        return result;
    }

    private EvaluationReport toAggregateReport(EvaluationSnapshot snapshot, List<EvaluationSnapshot> history) {
        EvaluationReport report = new EvaluationReport(
                snapshot.getTimestamp(),
                snapshot.getQueryCount(),
                snapshot.getTopK(),
                snapshot.getRecallAtK(),
                snapshot.getPrecisionAtK(),
                snapshot.getMrr(),
                snapshot.getAvgLatencyMs(),
                snapshot.getP95LatencyMs(),
                snapshot.getP99LatencyMs(),
                snapshot.getMinLatencyMs(),
                snapshot.getMaxLatencyMs(),
                snapshot.getKeywordCoverage(),
                snapshot.getQueriesWithRelevantResult(),
                List.of());
        report.setProfileName(snapshot.getProfileName());
        report.setDatasetVersion(snapshot.getDatasetVersion());
        report.setCategorySummaries(List.of());
        report.setComparison(null);
        report.setHistory(history);
        report.setAvgRetrievedContextChars(snapshot.getAvgContextChars());
        report.setAvgRetrievedContextTokens(snapshot.getAvgContextTokens());
        report.setReason(null);
        return report;
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

    private void addHistorySnapshot(String profileName, EvaluationSnapshot snapshot) {
        Deque<EvaluationSnapshot> history = reportHistories.computeIfAbsent(
                profileName, ignored -> new ArrayDeque<>());
        history.addLast(snapshot);
        while (history.size() > MAX_HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    private List<String> safeKeywords(TestCase testCase) {
        if (testCase == null || testCase.getExpectedKeywords() == null) {
            return List.of();
        }
        return testCase.getExpectedKeywords();
    }

    private String normalizeProfile(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return DEFAULT_PROFILE_NAME;
        }
        return profileName.trim();
    }

    private Long normalizeNovelId(Long novelId) {
        return novelId == null || novelId < 0 ? 0L : novelId;
    }

    private String historyKey(String profileName, Long novelId) {
        return normalizeProfile(profileName) + "|" + normalizeNovelId(novelId);
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

    private record DatasetDefinition(String resourceName, String datasetVersion) {
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
        private String reason;

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
            return empty(DEFAULT_PROFILE_NAME, DEFAULT_DATASET_VERSION, reason);
        }

        public static EvaluationReport empty(String profileName, String datasetVersion, String reason) {
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
            r.profileName = profileName;
            r.datasetVersion = datasetVersion;
            r.reason = reason;
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
        public String getReason() { return reason; }

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

        public void setReason(String reason) {
            this.reason = reason;
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
        private double minLatencyMs;
        private double maxLatencyMs;
        private double keywordCoverage;
        private int queriesWithRelevantResult;
        private double avgContextChars;
        private double avgContextTokens;
        private Long novelId;
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
        public double getMinLatencyMs() { return minLatencyMs; }
        public double getMaxLatencyMs() { return maxLatencyMs; }
        public double getKeywordCoverage() { return keywordCoverage; }
        public int getQueriesWithRelevantResult() { return queriesWithRelevantResult; }
        public double getAvgContextChars() { return avgContextChars; }
        public double getAvgContextTokens() { return avgContextTokens; }
        public Long getNovelId() { return novelId; }
        public String getProfileName() { return profileName; }
        public String getDatasetVersion() { return datasetVersion; }
        public void setProfileName(String profileName) { this.profileName = profileName; }
        public void setDatasetVersion(String datasetVersion) { this.datasetVersion = datasetVersion; }
        public void setMinLatencyMs(double minLatencyMs) { this.minLatencyMs = minLatencyMs; }
        public void setMaxLatencyMs(double maxLatencyMs) { this.maxLatencyMs = maxLatencyMs; }
        public void setAvgContextChars(double avgContextChars) { this.avgContextChars = avgContextChars; }
        public void setAvgContextTokens(double avgContextTokens) { this.avgContextTokens = avgContextTokens; }
        public void setNovelId(Long novelId) { this.novelId = novelId; }
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
