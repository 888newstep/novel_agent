package com.novel.agent.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * RAG 评测聚合快照。
 *
 * 只保存可用于趋势分析的标量指标，不保存查询详情、召回原文或小说正文。
 */
@Entity
@Table(name = "rag_evaluation_snapshots", indexes = {
        @Index(name = "idx_rag_eval_profile_novel_time", columnList = "profile_name, novel_id, evaluated_at"),
        @Index(name = "idx_rag_eval_profile_time", columnList = "profile_name, evaluated_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RagEvaluationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "profile_name", nullable = false, length = 100)
    private String profileName;

    @Column(name = "dataset_version", nullable = false, length = 50)
    private String datasetVersion;

    @Column(name = "novel_id", nullable = false)
    private Long novelId;

    @Column(name = "top_k", nullable = false)
    private Integer topK;

    @Column(name = "query_count", nullable = false)
    private Integer queryCount;

    @Column(name = "queries_with_relevant_result", nullable = false)
    private Integer queriesWithRelevantResult;

    @Column(name = "recall_at_k", nullable = false)
    private Double recallAtK;

    @Column(name = "precision_at_k", nullable = false)
    private Double precisionAtK;

    @Column(name = "mrr", nullable = false)
    private Double mrr;

    @Column(name = "avg_latency_ms", nullable = false)
    private Double avgLatencyMs;

    @Column(name = "p95_latency_ms", nullable = false)
    private Double p95LatencyMs;

    @Column(name = "p99_latency_ms", nullable = false)
    private Double p99LatencyMs;

    @Column(name = "min_latency_ms", nullable = false)
    private Double minLatencyMs;

    @Column(name = "max_latency_ms", nullable = false)
    private Double maxLatencyMs;

    @Column(name = "keyword_coverage", nullable = false)
    private Double keywordCoverage;

    @Column(name = "avg_context_chars", nullable = false)
    private Double avgContextChars;

    @Column(name = "avg_context_tokens", nullable = false)
    private Double avgContextTokens;

    @Column(name = "evaluated_at", nullable = false)
    private Long evaluatedAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
