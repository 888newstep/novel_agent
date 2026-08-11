# Observability

Updated: 2026-08-11

## Goal

`novel_agent` exposes operational RAG signals in a standard Prometheus format instead of keeping every metric inside application logs or the in-memory report.

## Endpoints

With the application running locally:

```powershell
Invoke-WebRequest http://localhost:8080/actuator/prometheus
Invoke-WebRequest http://localhost:8080/actuator/metrics/novel.agent.rag.evaluations
```

The Prometheus scrape endpoint is `GET /actuator/prometheus`. The metrics endpoint is useful for debugging a single meter; Prometheus should scrape the former for time-series storage.

## Exported Metrics

The custom meters use a bounded `profile` tag. `novelId`, query text, retrieved content, and user-provided values are never metric labels.

| Prometheus metric | Type | Meaning |
|---|---|---|
| `novel_agent_rag_evaluations_total` | counter | Completed RAG evaluations by profile |
| `novel_agent_rag_query_latency_seconds` | timer | Individual query latency distribution by profile |
| `novel_agent_rag_evaluation_skipped_total` | counter | Evaluations rejected before execution; reason is `unknown_profile`, `empty_dataset`, or `invalid_top_k` |
| `novel_agent_rag_snapshot_persistence_failures_total` | counter | Best-effort MySQL snapshot write failures by profile |
| `novel_agent_rag_latest_recall_at_k` | gauge | Latest restored or completed Recall@K |
| `novel_agent_rag_latest_precision_at_k` | gauge | Latest restored or completed Precision@K |
| `novel_agent_rag_latest_mrr` | gauge | Latest restored or completed MRR |
| `novel_agent_rag_latest_keyword_coverage` | gauge | Latest keyword coverage |
| `novel_agent_rag_latest_avg_latency_ms` | gauge | Latest average query latency |
| `novel_agent_rag_latest_p95_latency_ms` | gauge | Latest P95 query latency |
| `novel_agent_rag_latest_p99_latency_ms` | gauge | Latest P99 query latency |
| `novel_agent_rag_latest_query_count` | gauge | Query count in the latest report |
| `novel_agent_rag_latest_evaluated_at_epoch_seconds` | gauge | Evaluation timestamp of the latest report |

Micrometer converts the timer into Prometheus `_count`, `_sum`, and `_max` series. The application tag is added globally from `spring.application.name`.

## Prometheus Scrape Example

```yaml
scrape_configs:
  - job_name: novel-agent
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: [host.docker.internal:8080]
```

## Design Constraints

- `profile` is normalized to a safe allowlisted shape; malformed or unbounded values map to `unknown`.
- MySQL aggregate snapshots restore the latest gauges after an application restart.
- Counters and timers are process-local and reset when the JVM restarts; Prometheus provides durable history after scraping.
- A failed snapshot write increments a metric but never blocks the evaluation response.
- Metrics contain no novel source text, retrieved context, prompts, or API credentials.

## Verification

`RagEvaluationMetricsTest` uses `SimpleMeterRegistry` to verify counter values, timer totals, latest gauges, skip reasons, and the absence of a `novelId` tag. A live endpoint smoke requires a reachable MySQL database and Milvus dependency because the application fails fast when the Milvus client cannot connect.
