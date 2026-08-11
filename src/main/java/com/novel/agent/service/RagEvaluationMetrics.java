package com.novel.agent.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToDoubleFunction;

/**
 * Exposes low-cardinality RAG metrics for Prometheus scraping.
 *
 * <p>Only the allowlisted evaluation profile is used as a tag. User-controlled
 * novel identifiers, query text, and retrieved content are deliberately not
 * included in metric labels.</p>
 */
@Component
public class RagEvaluationMetrics {

    private static final String PROFILE_TAG = "profile";
    private static final String UNKNOWN_PROFILE = "unknown";
    private static final int MAX_PROFILE_TAG_LENGTH = 64;
    private static final Set<String> ALLOWED_PROFILE_TAGS = Set.of(
            RagEvaluationService.DEFAULT_PROFILE_NAME,
            RagEvaluationService.CHINESE_LIVE_PROFILE_NAME
    );

    private final MeterRegistry meterRegistry;
    private final ConcurrentMap<String, ProfileMeters> profileMeters = new ConcurrentHashMap<>();

    public RagEvaluationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
    }

    public void recordQueryLatency(String profileName, long elapsedMs) {
        profileMeters(profileName).queryLatency().record(Duration.ofMillis(Math.max(0L, elapsedMs)));
    }

    public void recordEvaluation(String profileName,
                                 int topK,
                                 int queryCount,
                                 double recallAtK,
                                 double precisionAtK,
                                 double mrr,
                                 double keywordCoverage,
                                 double avgLatencyMs,
                                 double p95LatencyMs,
                                 double p99LatencyMs,
                                 long evaluatedAt) {
        ProfileMeters meters = profileMeters(profileName);
        meters.evaluationCounter().increment();
        meters.updateLatest(topK, queryCount, recallAtK, precisionAtK, mrr,
                keywordCoverage, avgLatencyMs, p95LatencyMs, p99LatencyMs, evaluatedAt);
    }

    public void restoreLatest(String profileName,
                              int topK,
                              int queryCount,
                              double recallAtK,
                              double precisionAtK,
                              double mrr,
                              double keywordCoverage,
                              double avgLatencyMs,
                              double p95LatencyMs,
                              double p99LatencyMs,
                              long evaluatedAt) {
        profileMeters(profileName).updateLatest(topK, queryCount, recallAtK, precisionAtK, mrr,
                keywordCoverage, avgLatencyMs, p95LatencyMs, p99LatencyMs, evaluatedAt);
    }

    public void recordSkipped(String profileName, SkipReason reason) {
        ProfileMeters meters = profileMeters(profileName);
        meters.skippedCounters().getOrDefault(
                reason == null ? SkipReason.UNKNOWN_PROFILE : reason,
                meters.skippedCounters().get(SkipReason.UNKNOWN_PROFILE)
        ).increment();
    }

    public void recordPersistenceFailure(String profileName) {
        profileMeters(profileName).persistenceFailures().increment();
    }

    private ProfileMeters profileMeters(String profileName) {
        return profileMeters.computeIfAbsent(normalizeProfileTag(profileName),
                profile -> new ProfileMeters(profile, meterRegistry));
    }

    private static String normalizeProfileTag(String profileName) {
        if (profileName == null || profileName.isBlank()) {
            return UNKNOWN_PROFILE;
        }
        String candidate = profileName.trim();
        if (candidate.length() > MAX_PROFILE_TAG_LENGTH ||
                !candidate.matches("[A-Za-z0-9._-]+") ||
                !ALLOWED_PROFILE_TAGS.contains(candidate)) {
            return UNKNOWN_PROFILE;
        }
        return candidate;
    }

    public enum SkipReason {
        UNKNOWN_PROFILE,
        EMPTY_DATASET,
        INVALID_TOP_K
    }

    private static final class ProfileMeters {
        private final Counter evaluationCounter;
        private final Timer queryLatency;
        private final Counter persistenceFailures;
        private final Map<SkipReason, Counter> skippedCounters;

        private volatile double topK;
        private volatile double queryCount;
        private volatile double recallAtK;
        private volatile double precisionAtK;
        private volatile double mrr;
        private volatile double keywordCoverage;
        private volatile double avgLatencyMs;
        private volatile double p95LatencyMs;
        private volatile double p99LatencyMs;
        private volatile double evaluatedAtEpochSeconds;

        private ProfileMeters(String profile, MeterRegistry meterRegistry) {
            evaluationCounter = Counter.builder("novel.agent.rag.evaluations")
                    .description("Completed RAG evaluations")
                    .tag(PROFILE_TAG, profile)
                    .register(meterRegistry);
            queryLatency = Timer.builder("novel.agent.rag.query.latency")
                    .description("Latency of individual RAG evaluation queries")
                    .tag(PROFILE_TAG, profile)
                    .register(meterRegistry);
            persistenceFailures = Counter.builder("novel.agent.rag.snapshot.persistence.failures")
                    .description("RAG aggregate snapshot persistence failures")
                    .tag(PROFILE_TAG, profile)
                    .register(meterRegistry);

            EnumMap<SkipReason, Counter> counters = new EnumMap<>(SkipReason.class);
            for (SkipReason reason : SkipReason.values()) {
                counters.put(reason, Counter.builder("novel.agent.rag.evaluation.skipped")
                        .description("RAG evaluations skipped before execution")
                        .tag(PROFILE_TAG, profile)
                        .tag("reason", reason.name().toLowerCase())
                        .register(meterRegistry));
            }
            skippedCounters = Map.copyOf(counters);

            registerGauge(meterRegistry, "novel.agent.rag.latest.top.k", this, ProfileMeters::topK);
            registerGauge(meterRegistry, "novel.agent.rag.latest.query.count", this, ProfileMeters::queryCount);
            registerGauge(meterRegistry, "novel.agent.rag.latest.recall.at.k", this, ProfileMeters::recallAtK);
            registerGauge(meterRegistry, "novel.agent.rag.latest.precision.at.k", this, ProfileMeters::precisionAtK);
            registerGauge(meterRegistry, "novel.agent.rag.latest.mrr", this, ProfileMeters::mrr);
            registerGauge(meterRegistry, "novel.agent.rag.latest.keyword.coverage", this, ProfileMeters::keywordCoverage);
            registerGauge(meterRegistry, "novel.agent.rag.latest.avg.latency.ms", this, ProfileMeters::avgLatencyMs);
            registerGauge(meterRegistry, "novel.agent.rag.latest.p95.latency.ms", this, ProfileMeters::p95LatencyMs);
            registerGauge(meterRegistry, "novel.agent.rag.latest.p99.latency.ms", this, ProfileMeters::p99LatencyMs);
            registerGauge(meterRegistry, "novel.agent.rag.latest.evaluated.at.epoch.seconds",
                    this, ProfileMeters::evaluatedAtEpochSeconds);
        }

        private static void registerGauge(MeterRegistry meterRegistry,
                                           String name,
                                           ProfileMeters state,
                                           ToDoubleFunction<ProfileMeters> valueFunction) {
            Gauge.builder(name, state, valueFunction)
                    .tag(PROFILE_TAG, state.profile())
                    .register(meterRegistry);
        }

        private String profile() {
            return evaluationCounter.getId().getTag(PROFILE_TAG);
        }

        private void updateLatest(int topK,
                                  int queryCount,
                                  double recallAtK,
                                  double precisionAtK,
                                  double mrr,
                                  double keywordCoverage,
                                  double avgLatencyMs,
                                  double p95LatencyMs,
                                  double p99LatencyMs,
                                  long evaluatedAt) {
            this.topK = topK;
            this.queryCount = queryCount;
            this.recallAtK = recallAtK;
            this.precisionAtK = precisionAtK;
            this.mrr = mrr;
            this.keywordCoverage = keywordCoverage;
            this.avgLatencyMs = avgLatencyMs;
            this.p95LatencyMs = p95LatencyMs;
            this.p99LatencyMs = p99LatencyMs;
            this.evaluatedAtEpochSeconds = evaluatedAt <= 0 ? 0D : evaluatedAt / 1000D;
        }

        private Counter evaluationCounter() {
            return evaluationCounter;
        }

        private Timer queryLatency() {
            return queryLatency;
        }

        private Counter persistenceFailures() {
            return persistenceFailures;
        }

        private Map<SkipReason, Counter> skippedCounters() {
            return skippedCounters;
        }

        private double topK() {
            return topK;
        }

        private double queryCount() {
            return queryCount;
        }

        private double recallAtK() {
            return recallAtK;
        }

        private double precisionAtK() {
            return precisionAtK;
        }

        private double mrr() {
            return mrr;
        }

        private double keywordCoverage() {
            return keywordCoverage;
        }

        private double avgLatencyMs() {
            return avgLatencyMs;
        }

        private double p95LatencyMs() {
            return p95LatencyMs;
        }

        private double p99LatencyMs() {
            return p99LatencyMs;
        }

        private double evaluatedAtEpochSeconds() {
            return evaluatedAtEpochSeconds;
        }
    }
}
