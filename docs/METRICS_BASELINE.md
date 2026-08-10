# Metrics Baseline

Updated: 2026-08-10

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
| Import retry budget | Verified | `max-retries=3`, `retry-backoff-ms=1000` | `src/main/resources/application.yml` |
| Import batch size | Verified | `18` | `src/main/resources/application.yml` |
| Cost-governance scopes | Verified | per request, per novel, per model, daily, monthly | `src/main/java/com/novel/agent/service/TokenCostService.java` |
| Cost degradation strategies | Verified | budget fallback, model fallback, embedding fallback | `src/main/java/com/novel/agent/controller/NovelController.java`, `src/main/java/com/novel/agent/service/DeepSeekService.java`, `src/main/java/com/novel/agent/service/EmbeddingService.java` |
| Public import throughput number | Pending Benchmark | not pinned yet | this document |
| Public retrieval latency number on real env | Ready To Measure | service already reports `Avg`, `P95`, `P99` | `docs/BENCHMARK_REPORT.md` |
| Public token-cost before/after comparison | Ready To Measure | accounting and degradation events available | `docs/COST_GOVERNANCE_CASE.md` |
| CI retrieval contract result | Verified | 15 cases, `Recall@3=100%`, `Precision@3=100%`, `MRR=1.000` in deterministic fixture | `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java` |
| Reproducible live evaluation command | Verified | API runner writes the complete report and prints the metric summary | `scripts/run-rag-evaluation.ps1` |

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
  - idempotent batch retry cleanup by chapter range
  - final failure propagation after retry budget exhaustion

### Public Benchmark Status

- current public throughput number: not yet pinned
- benchmark environment required for next update:
  - active embedding provider
  - reachable Milvus instance
  - representative training dataset
  - stable host specification

### Next Number To Capture

Recommended public metric block:

- dataset size
- total import time
- average records per second
- average segments per second
- retry count
- flush count
- failure count

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

### Current Public Baseline

- dataset version: `2026-08-09`
- scenario count: 5
- case count: 15
- stable profile name: `writing-default-v1`

### Evidence Files

- `docs/BENCHMARK_REPORT.md`
- `src/main/resources/rag_eval_dataset.json`
- `src/test/resources/rag_eval_dataset.json`
- `scripts/run-rag-evaluation.ps1`

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
