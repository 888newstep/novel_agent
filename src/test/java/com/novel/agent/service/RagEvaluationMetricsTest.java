package com.novel.agent.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationMetricsTest {

    @Test
    void recordsEvaluationAndQueryMetricsWithoutHighCardinalityTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagEvaluationMetrics metrics = new RagEvaluationMetrics(registry);

        metrics.recordQueryLatency("writing-zh-live-v1", 12);
        metrics.recordQueryLatency("writing-zh-live-v1", 28);
        metrics.recordEvaluation(
                "writing-zh-live-v1", 5, 15,
                0.80, 0.413333, 0.755555, 0.63,
                307.733, 887.0, 887.0, 1_760_000_000_000L
        );
        metrics.recordPersistenceFailure("writing-zh-live-v1");

        assertEquals(1.0, registry.get("novel.agent.rag.evaluations")
                .tag("profile", "writing-zh-live-v1").counter().count());
        assertEquals(2L, registry.get("novel.agent.rag.query.latency")
                .tag("profile", "writing-zh-live-v1").timer().count());
        assertEquals(40.0, registry.get("novel.agent.rag.query.latency")
                .tag("profile", "writing-zh-live-v1").timer()
                .totalTime(TimeUnit.MILLISECONDS), 0.0001);
        assertEquals(0.80, registry.get("novel.agent.rag.latest.recall.at.k")
                .tag("profile", "writing-zh-live-v1").gauge().value(), 0.0001);
        assertEquals(887.0, registry.get("novel.agent.rag.latest.p99.latency.ms")
                .tag("profile", "writing-zh-live-v1").gauge().value(), 0.0001);
        assertEquals(1.0, registry.get("novel.agent.rag.snapshot.persistence.failures")
                .tag("profile", "writing-zh-live-v1").counter().count());
        assertTrue(registry.getMeters().stream()
                .noneMatch(meter -> meter.getId().getTags().stream()
                        .anyMatch(tag -> tag.getKey().equals("novelId"))));
    }

    @Test
    void normalizesUnexpectedProfilesAndKeepsSkipReasonsBounded() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RagEvaluationMetrics metrics = new RagEvaluationMetrics(registry);

        metrics.recordSkipped("untrusted-profile-123", RagEvaluationMetrics.SkipReason.INVALID_TOP_K);

        assertEquals(1.0, registry.get("novel.agent.rag.evaluation.skipped")
                .tag("profile", "unknown")
                .tag("reason", "invalid_top_k")
                .counter().count());
        assertFalse(registry.getMeters().stream()
                .anyMatch(meter -> "untrusted-profile-123".equals(meter.getId().getTag("profile"))));
    }
}
