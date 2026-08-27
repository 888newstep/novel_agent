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

- current public throughput number: verified on 2026-08-11 with a pinned-host, isolated live run
- latest live sample: 1,000 schema-aligned Chinese writing-memory records -> 2,000 segments in `55.227s` service time
- latest throughput: `18.11 records/s` and `36.21 segments/s`
- latest reliability counters: `0` retries, `4` flushes, `0` failures, checkpoint removed, cleanup succeeded
- remaining evidence boundary for a production capacity claim:
  - representative real training dataset
  - target deployment host specification
  - larger scale and repeated runs

### Latest Larger Operational Run

- sample: 600 JSONL records -> 1200 segments
- service duration: `37.853s`; wall-clock duration: `39.223s`
- throughput: `15.85 records/s`; `31.7 segments/s`
- reliability: `0` retries, `0` failures, cleanup succeeded
- evidence: `docs/benchmarks/import-benchmark-large-live-20260810.json`
- interpretation: operational evidence only; do not extrapolate linearly to 50K records

### Pinned-Host Representative Run

- sample: 1,000 deterministic schema-aligned Chinese writing-memory records -> 2,000 segments
- service duration: `55.227s`; wall-clock duration: `55.745s`
- throughput: `18.11 records/s`; `36.21 segments/s`
- reliability: `0` retries, `0` failures, `4` flushes, cleanup succeeded
- host: Windows 11 Home Chinese Edition `10.0.26200`, i5-13500H, 12 physical cores / 16 logical processors, `15.73 GiB` visible memory
- runtime: Java `17.0.12`, Maven `3.9.9`, local MySQL `8.0.46`, cloud Milvus, SiliconFlow `BAAI/bge-m3`
- import configuration: batch size `18`, max retries `3`, retry backoff `1000ms`
- scenario distribution: five writing-memory scenarios, `200` records each
- evidence: `docs/benchmarks/import-benchmark-representative-live-20260811.json` and `scripts/generate-representative-import-dataset.ps1`
- interpretation: this closes the pinned-host evidence task for a bounded schema-aligned baseline; it does not justify linear extrapolation to 50K records or production capacity.

The benchmark uses `/api/import/training-data/{novelId}` with a temporary positive `novelId`; the shared corpus was not modified and all temporary vectors were deleted afterward.

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

The 2026-08-10 and 2026-08-11 samples are operational baselines, not a 50K-record capacity claim. Generate the deterministic corpus with `scripts/generate-representative-import-dataset.ps1`, then run the isolated benchmark with cleanup enabled.

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
- current baseline (R2-ZH-LIVE-20260827): Recall@5 warmed stable-state `86.7%`; three-run mean `88.9%`; Precision@5 `45.3%`; MRR `0.806`; keyword coverage `81.5%`; warmed latency `82.5ms` / `P95=131ms`
- historical baseline (R1-ZH-LIVE-20260810): Recall@5 `73.3%` in all three runs; Precision@5 `38.7%–40.0%` (mean `39.6%`); MRR `0.667–0.700` (mean `0.689`); keyword coverage `63.0%`; average latency `60.7–210.3ms`
- latency: first run is cold-started and later runs are warmed; R2 warmed `82.5ms` / `P95=131ms` (cold first run had a 48s P95 spike)
- scenario signal (R2): `unresolved_event` recovered to `100%` Recall@5 (was `33.3%`); `character_profile` is now the weakest bucket at `66.7%`
- hard cases (R2 stable): `少宫主` and `萧师兄` (character_profile); historical R1 hard cases `天极门 被灭宗`, `火祖洞天 七狱塔`, and `黑皇城 少宫主` are now resolved
- evidence: `docs/benchmarks/rag-evaluation-zh-live-20260810.json`, `docs/benchmarks/rag-evaluation-zh-live-20260827.json`
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
