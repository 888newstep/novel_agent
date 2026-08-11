-- RAG evaluation history stores aggregate metrics only.
-- Query details and novel source text are intentionally excluded.
CREATE TABLE IF NOT EXISTS rag_evaluation_snapshots (
    id                             BIGINT AUTO_INCREMENT PRIMARY KEY,
    profile_name                   VARCHAR(100) NOT NULL,
    dataset_version                VARCHAR(50) NOT NULL,
    novel_id                       BIGINT NOT NULL,
    top_k                          INT NOT NULL,
    query_count                    INT NOT NULL,
    queries_with_relevant_result   INT NOT NULL,
    recall_at_k                    DOUBLE NOT NULL,
    precision_at_k                 DOUBLE NOT NULL,
    mrr                            DOUBLE NOT NULL,
    avg_latency_ms                 DOUBLE NOT NULL,
    p95_latency_ms                 DOUBLE NOT NULL,
    p99_latency_ms                 DOUBLE NOT NULL,
    min_latency_ms                 DOUBLE NOT NULL,
    max_latency_ms                 DOUBLE NOT NULL,
    keyword_coverage               DOUBLE NOT NULL,
    avg_context_chars              DOUBLE NOT NULL,
    avg_context_tokens             DOUBLE NOT NULL,
    evaluated_at                   BIGINT NOT NULL,
    created_at                     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_rag_eval_profile_novel_time (profile_name, novel_id, evaluated_at),
    INDEX idx_rag_eval_profile_time (profile_name, evaluated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
