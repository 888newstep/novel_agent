# `novel_agent` Benchmark Report

Updated: 2026-08-10

## Purpose

This document records retrieval quality, latency, and token-cost evidence for `novel_agent`.
It is used both for optimization and for interview presentation.

## Benchmark Scope

Focus on writing-specific retrieval scenarios instead of generic QA benchmarks.

Recommended scenario buckets:

- character profile retrieval
- unresolved event recall
- world setting retrieval
- item or skill retrieval
- pre-writing context assembly

## Fixed Metrics

Record the following in every round:

- `Recall@K`
- `Precision@K`
- `MRR`
- keyword coverage
- average latency
- `P95`
- `P99`
- average retrieved context characters and estimated tokens
- generation token cost from the generation trace, when the round includes generation

## Baseline Table

| Round | Date | Dataset Size | Scenario Focus | TopK | Recall@K | Precision@K | MRR | Avg Latency | P95 | P99 | Avg Context Tokens | Avg Cost | Notes |
|------|------|--------------|----------------|------|-----------|--------------|-----|-------------|-----|-----|--------------------|----------|-------|
| R0-CONTRACT | 2026-08-10 | 15 | metric aggregation fixture | 3 | 100% | 100% | 1.000 | not representative | not representative | not representative | emitted | n/a | CI contract: `RagEvaluationServiceTest` |
| R1-FIXTURE | 2026-08-10 | 1 | continuation ranking | 1 | 100% after ranking | n/a | n/a | not measured | not measured | not measured | n/a | n/a | Top-1 relevant result: `0/1 -> 1/1` |
| R0-LIVE-20260810 | 2026-08-10 | 15 | cloud Milvus operational run | 5 | 0%* | 0%* | 0.000* | 256.1ms | 692ms | 692ms | 563 | n/a | 15/15 queries returned 5 candidates; semantic score is not comparable* |
| R1-ZH-PROFILE | 2026-08-10 | 15 | Chinese corpus-aligned profile implementation | 5 | pending | pending | pending | blocked | blocked | blocked | pending | n/a | `writing-zh-live-v1` loaded in CI; live run blocked by Milvus TCP reachability |

`R0-CONTRACT` and `R1-FIXTURE` are deterministic CI evidence, not production latency claims. A live row must be recorded only after the embedding provider, Milvus instance, host, dataset, and parameters are written down.

### R0-LIVE-20260810 Evidence Note

- environment: local MySQL, cloud Milvus, SiliconFlow `BAAI/bge-m3` embedding provider
- parameters: dataset version `2026-08-09`, profile `writing-default-v1`, `novelId=0`, `TopK=5`
- operational result: all 15 API-backed queries completed and returned 5 candidates; average latency was `256.1ms`, `P95=692ms`, `P99=692ms`, and average retrieved context was `563` estimated tokens
- semantic caveat: the fixed evaluation cases use English keywords for a writing-memory fixture, while the live `novelId=0` corpus is an existing Chinese training-data collection. The `0%` recall/precision/MRR values are therefore marked `*` and must not be interpreted as an algorithm regression
- reproducibility: run `scripts/run-rag-evaluation.ps1` against the same environment after loading a corpus aligned with `rag_eval_dataset.json`

## Corpus-Aligned Chinese Profile

### R1-ZH-PROFILE-20260810

- profile: `writing-zh-live-v1`
- dataset: `src/main/resources/rag_eval_dataset_zh.json`, version `2026-08-10`, 15 cases across the five writing scenarios
- validity rule: expected keywords were selected from the observed Chinese production-like corpus; the old English fixture remains unchanged for CI contract comparisons
- isolation rule: report history and comparison deltas are maintained per profile, so switching between English and Chinese datasets cannot create a false regression delta
- implementation evidence: `RagEvaluationServiceTest`, `RagEvaluationControllerTest`, and `GET /api/v1/novel/evaluate/profiles`
- live status: the 2026-08-10 attempt was blocked before query execution because the configured Milvus TCP endpoint was unreachable; semantic and latency fields are intentionally not reported
- blocked-run evidence: `docs/benchmarks/rag-evaluation-zh-live-20260810.json`
## Import Throughput Baseline

### IMPORT-LIVE-20260810

| Field | Value |
|------|-------|
| Input format | JSON lines |
| Source records | 60 |
| Imported records | 60 |
| Stored segments | 120 |
| Service duration | `8.176s` |
| Wall-clock duration | `10.264s` |
| Records per second | `7.34` |
| Segments per second | `14.68` |
| Batch count | 7 |
| Flush count | 1 |
| Retry count | 0 |
| Failure count | 0 |
| Checkpoint after completion | removed |
| Isolation | positive temporary `novelId`, cleaned from all Milvus collections |

- environment: local MySQL, cloud Milvus, and configured cloud embedding provider
- batch configuration: `milvus.write.batch-size=18`, `max-retries=3`, `retry-backoff-ms=1000`
- scope: a 60-record operational sample; this is not a 50K capacity extrapolation
- evidence: `scripts/run-import-benchmark.ps1` and the committed snapshot `docs/benchmarks/import-benchmark-live-20260810.json`
- decision: keep the `novelId`-isolated benchmark path and repeat with a larger dataset before making capacity claims

## Token Cost Governance Baseline

### COST-GOVERNANCE-20260810

This is a deterministic service-level fixture, not a real provider billing report.

| Mode | Accepted | Blocked | Billable tokens | Estimated cost |
|------|----------|---------|-----------------|----------------|
| strict mode off | 4 | 0 | 40 | 4.0 |
| strict mode on, daily token budget 20 | 2 | 2 | 20 | 2.0 |

- replay size: 4 identical writing requests
- measured delta: `50%` fewer billable tokens and `50%` lower estimated cost
- evidence: `src/test/java/com/novel/agent/service/CostGovernanceBenchmarkTest.java`, `scripts/run-cost-governance-benchmark.ps1`, and `docs/benchmarks/cost-governance-benchmark-20260810.json`
- caveat: synthetic pricing is used to make the behavior delta visible; it must not be interpreted as provider pricing

## Reproducible Live Run

The API-backed runner keeps the benchmark command out of oral explanations:

```powershell
pwsh -File scripts/run-rag-evaluation.ps1 `
  -BaseUrl http://localhost:8080 `
  -NovelId 0 `
  -TopK 5 `
  -Profile writing-zh-live-v1 `
  -OutputPath artifacts/rag-report.json
```

The command prints the selected profile, dataset version, recall metrics, latency percentiles, and retrieved-context size. Use the default profile for the English CI fixture or `writing-zh-live-v1` for the Chinese production-like corpus. It writes the complete response when `-OutputPath` is supplied. A live run requires a reachable MySQL database, Milvus collection, and configured embedding provider.

For import throughput, use a temporary positive `novelId` and clean it after the run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-import-benchmark.ps1 `
  -FilePath C:/datasets/novel_cn_sample.jsonl `
  -BaseUrl http://localhost:8080 `
  -NovelId 926345375 `
  -OutputPath artifacts/import-benchmark-live.json `
  -Cleanup
```

## Optimization Log Template

Use one block per optimization round.

- change: what ranking or filtering logic changed
- hypothesis: why the change should help writing retrieval
- affected scenarios: which scenario bucket should improve
- result: which metrics moved and by how much
- side effect: any latency or cost tradeoff
- decision: keep, revert, or continue tuning

## Planned Rounds

### R0

- goal: lock one stable baseline before further tuning
- focus: current `Milvus` retrieval plus chapter-aware filters
- output: initial metrics table and one report snapshot

### R1

- goal: verify whether recent chapter weighting improves continuation context quality
- focus: chapter recency and future-chapter filtering
- expected gain: higher recall for continuation-writing cases

### R2

- goal: test whether unresolved hooks should receive explicit ranking boost
- focus: foreshadowing and unresolved event retrieval
- expected gain: better writing-memory usefulness for continuation generation

### R3

- goal: record why each result is recalled and scored
- focus: interpretability of retrieval decisions
- expected gain: easier debugging and stronger interview explanation

## Recorded Optimization Evidence

### R1-FIXTURE: ranking beyond raw vector score

- input: query `dragon oath`, current chapter `10`, `TopK=1`
- candidates: relevant chapter-8 segment with vector score `0.92`, distractor chapter-8 segment with score `0.95`, future chapter-11 segment with score `0.99`
- before: raw-score profile returns the distractor, so the continuation hit is `0/1`
- after: `writing-default-v1` returns the relevant segment, so the continuation hit is `1/1`
- explanation: `keyword_hits`, `exact_query_match`, and `chapter_distance=2`; the future chapter is filtered
- evidence: `MilvusSearchServiceTest.writingDefaultRankingImprovesContinuationTopOneAgainstRawVectorScore`
- tradeoff: ranking adds deterministic scoring work and richer response metadata; this fixture does not claim live latency improvement

## Evidence Standard

Do not claim optimization success unless both conditions are met:

- at least one scenario bucket improves with measurable evidence
- the latency or cost tradeoff is recorded clearly

For CI fixtures, label the result as fixture evidence. Do not reuse fixture latency as a production benchmark number.
