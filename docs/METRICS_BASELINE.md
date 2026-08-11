# Metrics Baseline

Updated: 2026-08-11

## Purpose

This document records the current measurable baseline for `novel_agent` and clarifies which metrics are already source-controlled versus which still require an environment-backed benchmark run.

## Reading Rule

Use the following labels consistently:

- `Verified`: directly supported by tests, code paths, or committed reports
- `Ready To Measure`: the system can already emit the metric, but the latest public benchmark number has not yet been pinned in the repo
- `Pending Benchmark`: evidence standard is defined, but a stable external run still needs to be recorded

## Baseline Snapshot

| Dimension | Status | Current baseline | Source |
|----------|--------|------------------|--------|
| Evaluation dataset size | Verified | 15 writing-specific cases | `src/main/resources/rag_eval_dataset.json` |
| Evaluation profile | Verified | `writing-default-v1` | `src/main/java/com/novel/agent/service/RagEvaluationService.java` |
| Retrieval metrics emitted | Verified | `Recall@K`, `Precision@K`, `MRR`, `Avg`, `P95`, `P99`, context chars/tokens | `src/main/java/com/novel/agent/service/RagEvaluationService.java` |
| Evaluation history persistence | Verified | MySQL aggregate snapshots keyed by `profile_name + novel_id`, with in-memory fallback; query details and novel text excluded | `src/main/java/com/novel/agent/entity/RagEvaluationSnapshot.java`, `sql/migrations/V20260810__add_rag_evaluation_snapshots.sql` |
| Prometheus RAG export | Verified | Actuator `/actuator/prometheus`; profile-scoped counters, query latency timer, latest quality/latency gauges, skip counters, and persistence-failure counter | `src/main/java/com/novel/agent/service/RagEvaluationMetrics.java`, `docs/OBSERVABILITY.md` |
| Import retry budget | Verified | `max-retries=3`, `retry-backoff-ms=1000` | `src/main/resources/application.yml` |
| Import batch size | Verified | `18` | `src/main/resources/application.yml` |
| Cost-governance scopes | Verified | per request, per novel, per model, daily, monthly | `src/main/java/com/novel/agent/service/TokenCostService.java` |
| Cost degradation strategies | Verified | budget fallback, model fallback, embedding fallback | `src/main/java/com/novel/agent/controller/NovelController.java`, `src/main/java/com/novel/agent/service/DeepSeekService.java`, `src/main/java/com/novel/agent/service/EmbeddingService.java` |
| Public import throughput number | Verified | 2026-08-10 live run: 60 source records, 120 segments, `7.34 records/s`, `14.68 segments/s`, `0` retries, `1` flush, `0` failures | `scripts/run-import-benchmark.ps1`, `docs/benchmarks/import-benchmark-live-20260810.json` |
| Public retrieval latency number on real env | Verified | 2026-08-10 live run: `Avg=256.1ms`, `P95=692ms`, `P99=692ms` | `docs/BENCHMARK_REPORT.md` |
| Public token-cost before/after comparison | Verified | deterministic 4-request fixture: strict governance reduces billable tokens and estimated cost by `50%`, blocks `2/4` requests | `scripts/run-cost-governance-benchmark.ps1`, `docs/benchmarks/cost-governance-benchmark-20260810.json` |
| CI retrieval contract result | Verified | 15 cases, `Recall@3=100%`, `Precision@3=100%`, `MRR=1.000` in deterministic fixture | `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java` |
| Reproducible live evaluation command | Verified | API runner writes the complete report and prints the metric summary | `scripts/run-rag-evaluation.ps1` |
| Milvus collection lifecycle | Verified | indexed collections auto-load after startup; unindexed collections are skipped and search degrades to an empty result | `src/main/java/com/novel/agent/config/MilvusLifecycleConfig.java`, `src/main/java/com/novel/agent/service/MilvusAdminService.java`, `src/main/java/com/novel/agent/service/MilvusSearchService.java` |

## Import Pipeline Metrics

### Current Engineering Envelope

- supported input styles:
  - JSON array
  - JSON lines
- progress observability:
  - current stage
  - processed records
  - total records
  - checkpoint path
  - batch count
  - flush count
  - retry metadata
- stability controls:
  - resumable checkpoint
  - idempotent batch retry cleanup by `novel_id` and chapter range
  - explicit `novelId` isolation for non-default imports
  - novel-scoped checkpoint suffix for isolated imports
  - final failure propagation after retry budget exhaustion

### Public Benchmark Status

- current public throughput number: verified on 2026-08-10 with isolated live runs at two sample sizes
- latest live sample: 600 JSONL records -> 1200 segments in `37.853s` service time
- latest throughput: `15.85 records/s` and `31.7 segments/s`
- latest reliability counters: `0` retries, `3` flushes, `0` failures, checkpoint removed
- benchmark environment required for the next capacity claim:
  - active embedding provider
  - reachable Milvus instance
  - representative training dataset
  - stable host specification

### Latest Larger Operational Run

- sample: 600 JSONL records -> 1200 segments
- service duration: `37.853s`; wall-clock duration: `39.223s`
- throughput: `15.85 records/s`; `31.7 segments/s`
- reliability: `0` retries, `0` failures, cleanup succeeded
- evidence: `docs/benchmarks/import-benchmark-large-live-20260810.json`
- interpretation: operational evidence only; do not extrapolate linearly to 50K records

### Next Scale Run To Capture

For the next larger import run, preserve the same public metric block and add the host specification:

- dataset size
- total import time
- average records per second
- average segments per second
- retry count
- flush count
- failure count

### Reproducible Import Benchmark

The benchmark uses `/api/import/training-data/{novelId}` so the default shared corpus is not modified. If `-NovelId` is omitted, the script generates an isolated positive id. Add `-Cleanup` to delete that id from all Milvus collections after a successful run.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-import-benchmark.ps1 `
  -FilePath C:/datasets/novel_cn_sample.jsonl `
  -BaseUrl http://localhost:8080 `
  -NovelId 926345375 `
  -OutputPath artifacts/import-benchmark-live.json `
  -Cleanup
```

The 2026-08-10 samples are operational baselines, not a 50K-record capacity claim. A larger run should preserve the same fields and record host, embedding model, dataset size, and cleanup id.

## Retrieval Metrics

### Already Implemented

The evaluation service and report model already support:

- `Recall@K`
- `Precision@K`
- `MRR`
- average latency
- `P95` latency
- `P99` latency
- average retrieved context characters and estimated tokens
- profile comparison
- category breakdown by writing scenario
- MySQL-backed aggregate history and restart restoration
- history API: `GET /api/v1/novel/evaluate/history`

### Current Public Baseline

- dataset version: `2026-08-09`
- scenario count: 5
- case count: 15
- stable profile name: `writing-default-v1`
- corpus-aligned profile: `writing-zh-live-v1` (dataset version `2026-08-10`, 15 Chinese cases)
- live semantic status: verified on 2026-08-10 with three sequential read-only runs; use the committed range/mean snapshot rather than a single cold-start call

### Live Operational Evidence

- run date: `2026-08-10`
- environment: local MySQL + cloud Milvus + SiliconFlow `BAAI/bge-m3`
- `TopK=5`, 15 queries, average latency `256.1ms`, `P95/P99=692ms`
- average retrieved context: `2250` characters / `563` estimated tokens
- the legacy English semantic row remains non-comparable to the Chinese corpus; the aligned `writing-zh-live-v1` profile now has a separate verified baseline


### Corpus-Aligned Chinese Profile Evidence

- profile: `writing-zh-live-v1`, dataset version `2026-08-10`, `novelId=0`, `TopK=5`
- sample: 15 queries repeated three times against reachable cloud Milvus; no vectors were written
- quality: Recall@5 `73.3%` in all three runs; Precision@5 `38.7%–40.0%` (mean `39.6%`); MRR `0.667–0.700` (mean `0.689`); keyword coverage `63.0%`
- latency: average `60.7–210.3ms`; P95 `73–567ms`; P99 `73–567ms`; first run is cold-started and later runs are warmed
- scenario signal: `item_skill` and `world_setting` are strongest in the representative run; `unresolved_event` is the next ranking-tuning target
- hard cases: `少宫主`, `天极门 被灭宗`, `火祖洞天 七狱塔`, and `黑皇城 少宫主`
- evidence: `docs/benchmarks/rag-evaluation-zh-live-20260810.json`
### Evidence Files

- `docs/BENCHMARK_REPORT.md`
- `src/main/resources/rag_eval_dataset.json`
- `src/main/resources/rag_eval_dataset_zh.json`
- `docs/benchmarks/rag-evaluation-zh-live-20260810.json`
- `src/test/resources/rag_eval_dataset.json`
- `scripts/run-rag-evaluation.ps1`
- `src/main/java/com/novel/agent/controller/RagEvaluationController.java`
- `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java`

## Token Cost Metrics

### Current Governance Dimensions

- per request
- per novel daily
- per model daily
- global daily
- global monthly
- token-based limit
- estimated USD-based limit

### Current Degradation Outputs

- `budget_limit -> outline_only_response`
- `model_failure -> direct_api_fallback`, then `outline_only_response`
- `embedding_failure -> ollama fallback`, then single-item retry path

### Verified Evidence

- degradation events are stored and exposed in dashboard summary
- scoped summaries are grouped by novel and model
- blocked requests are recorded instead of failing silently

Evidence:

- `src/main/java/com/novel/agent/service/TokenCostService.java`
- `src/test/java/com/novel/agent/service/TokenCostServiceTest.java`
- `src/test/java/com/novel/agent/controller/NovelControllerTest.java`

## How To Keep This Document Honest

Do not fill public benchmark numbers from mocked unit tests.
Only update throughput, latency, or cost values here when they come from:

- a reproducible local run with documented environment info, or
- a deployed environment run with recorded parameters

The deterministic fixture values in `docs/BENCHMARK_REPORT.md` are labeled as CI evidence and must not be presented as live Milvus latency.
