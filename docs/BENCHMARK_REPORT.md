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
| R1-ZH-PROFILE | 2026-08-10 | 15 | Chinese corpus-aligned profile and API contract | 5 | n/a | n/a | n/a | n/a | n/a | n/a | emitted | n/a | `writing-zh-live-v1` profile loaded and isolated in CI; live semantic evidence is recorded in `R1-ZH-LIVE-20260810` |
| R1-ZH-LIVE-20260810 | 2026-08-10 | 15 | Chinese corpus-aligned live retrieval | 5 | 73.3% | 39.6% | 0.689 | 111.6ms | 240ms | 240ms | 563 | n/a | Mean of 3 sequential read-only runs; first run cold-started; no vectors written |
| R2-ZH-LIVE-20260827 | 2026-08-27 | 15 | Chinese corpus re-baseline after retrieval streamline | 5 | 88.9% | 45.3% | 0.806 | 82.5ms* | 131ms* | 131ms* | 563 | n/a | Mean of 3 sequential read-only runs; warmed runs 2-3 stable at 86.7%; *latency = warmed runs only (cold first run had a 48s P95 spike); no vectors written |
| R3-ZH-LIVE-20260827 | 2026-08-27 | 15 | Chinese corpus re-baseline after Chinese tail-substring keyword extraction | 5 | 93.3% | 51.1% | 0.933 | 106ms* | 192ms* | 192ms* | 562 | n/a | Recall@5 93.3% in all 3 runs (14/15); 萧师兄 recovered via 师兄 sub-keyword; 少宫主 only unmatched; *latency = warmed runs only; no vectors written |
| R1-HISTORY-SMOKE-20260810 | 2026-08-10 | 15 | aggregate persistence and restart smoke | 5 | 80.0% | 41.3% | 0.756 | 307.7ms | 887ms | 887ms | not recorded | n/a | One read-only run; one MySQL snapshot row; report/history restored after restart; details absent |
| IMPORT-LIVE-20260810-LARGE | 2026-08-10 | 600 | isolated JSONL import | n/a | n/a | n/a | n/a | 38.0s | n/a | n/a | n/a | n/a | 1200 segments, 15.85 records/s, 31.7 segments/s, 0 retries, cleanup succeeded |
| IMPORT-LIVE-20260811-PINNED-REPRESENTATIVE | 2026-08-11 | 1000 | pinned-host schema-aligned Chinese import | n/a | n/a | n/a | n/a | 55.2s | n/a | n/a | n/a | n/a | 2000 segments, 18.11 records/s, 36.21 segments/s, 0 retries, cleanup succeeded |

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
- live status: verified on 2026-08-10 after the Milvus endpoint recovered; three sequential read-only evaluations completed against `novelId=0`
- aggregate result: Recall@5 was stable at `73.3%`; Precision@5 ranged from `38.7%` to `40.0%` with a three-run mean of `39.6%`; MRR ranged from `0.667` to `0.700` with a mean of `0.689`
- latency result: three-run mean was `111.6ms`, mean `P95=240ms`, and mean `P99=240ms`; the first cold run was `210.3ms` average / `567ms` P95, while warmed runs were `63.8ms` and `60.7ms` average
- scenario signal: `item_skill` and `world_setting` reached `100%` Recall@5 in the representative run; `unresolved_event` was the weakest bucket at `33.3%` Recall@5 and `0.333` MRR
- next tuning samples: `少宫主`, `天极门 被灭宗`, `火祖洞天 七狱塔`, and `黑皇城 少宫主` were not matched in the representative Top-5 results; keep them as hard cases instead of weakening labels
- safety: the run only searched `novelId=0`; no vectors were written
- live-run evidence: `docs/benchmarks/rag-evaluation-zh-live-20260810.json`

### R2-ZH-LIVE-20260827

- live status: re-verified on 2026-08-27 after the retrieval streamline commits (`7937926`, `74ec537`); three sequential read-only evaluations completed against `novelId=0`
- aggregate result: Recall@5 warmed stable-state `86.7%` (runs 2-3 identical); three-run mean `88.9%` (min `86.7` / max `93.3`); Precision@5 `45.3%`; MRR mean `0.806`; keyword coverage `81.5%`
- latency result: warmed runs average `82.5ms`, warmed `P95=131ms`, warmed `P99=131ms`; the cold first run had a `48s` P95 spike from embedding/Milvus warm-up, so warmed-only latency is the representative figure
- scenario signal: `unresolved_event` recovered to `100%` Recall@5 (was `33.3%`); `world_setting`, `item_skill`, and `prewriting_context` also at `100%`; `character_profile` is now the weakest bucket at `66.7%`
- next tuning samples: `少宫主` and `萧师兄` (character_profile) were not matched in the warmed Top-5 results; root causes: `少宫主` has a single relevant segment that stays below the vector candidate pool, and `萧师兄` lacks Chinese tokenization in keyword extraction
- safety: the run only searched `novelId=0`; no vectors were written
- live-run evidence: `docs/benchmarks/rag-evaluation-zh-live-20260827.json`

### R3-ZH-LIVE-20260827

- live status: re-verified on 2026-08-27 after `extractKeywords` gained Chinese tail-2-substring extraction (`萧师兄` fix); three sequential read-only evaluations completed against `novelId=0`
- aggregate result: Recall@5 `93.3%` in all three runs (14/15); Precision@5 mean `51.1%`; MRR mean `0.933`; keyword coverage `85.2%`
- latency result: warmed runs average `106.1ms`, warmed `P95=192ms`, warmed `P99=192ms`
- scenario signal: `character_profile` partially recovered to `66.7%`; `萧师兄` now matched at Top-1 via the extracted `师兄` sub-keyword; `少宫主` remains the only unmatched case
- root cause: Chinese role/title core words sit at the token tail (`师兄`/`宫主`/`长老`…); tail-2-substring extraction lets `萧师兄` hit corpus fragments that only contain `师兄`
- safety: the run only searched `novelId=0`; no vectors were written
- live-run evidence: `docs/benchmarks/rag-evaluation-zh-live-r3-20260827.json`

## RAG Evaluation History Persistence Smoke

### R1-HISTORY-SMOKE-20260810

- environment: local MySQL, cloud Milvus, and configured cloud embedding provider
- parameters: profile `writing-zh-live-v1`, dataset version `2026-08-10`, `novelId=0`, `TopK=5`, 15 queries
- evaluation result: Recall@5 `80.0%`, Precision@5 `41.3333%`, MRR `0.7556`, average latency `307.733ms`, `P95=887ms`, `P99=887ms`
- persistence result: one aggregate row was written to `rag_evaluation_snapshots`; after an application restart, `/report` restored `queryCount=15` and `/history` returned `count=1`
- privacy result: the persisted history response did not contain `details`, and the table stores no query text, retrieved content, or novel source text
- comparison caveat: this is a single persistence smoke run and is not a replacement for the three-run `R1-ZH-LIVE-20260810` latency baseline
- evidence: `docs/benchmarks/rag-evaluation-history-live-20260810.json`, `docs/RAG_EVALUATION_HISTORY.md`, and the service/controller persistence tests

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

### IMPORT-LIVE-20260810-LARGE

| Field | Value |
|------|-------|
| Input format | JSON lines |
| Source records | 600 |
| Imported records | 600 |
| Stored segments | 1200 |
| Service duration | `37.853s` |
| Wall-clock duration | `39.223s` |
| Records per second | `15.85` |
| Segments per second | `31.7` |
| Batch count | 67 |
| Flush count | 3 |
| Retry count | 0 |
| Failure count | 0 |
| Checkpoint after completion | removed |
| Isolation | temporary positive `novelId`, cleaned from all Milvus collections |

- environment: local MySQL, cloud Milvus, and configured cloud embedding provider
- scope: 600-record operational sample; this improves evidence over the 60-record baseline but is still not a 50K capacity extrapolation
- evidence: `scripts/run-import-benchmark.ps1` and `docs/benchmarks/import-benchmark-large-live-20260810.json`
- result: throughput increased from `7.34` to `15.85` records/s and from `14.68` to `31.7` segments/s; both runs completed with zero retries and zero failures
- caveat: the sample used generated content conforming to the existing JSONL contract; representative corpus and pinned host specifications are still required before capacity claims

### IMPORT-LIVE-20260811-PINNED-REPRESENTATIVE

| Field | Value |
|------|-------|
| Input format | JSON lines |
| Source records | 1000 |
| Imported records | 1000 |
| Stored segments | 2000 |
| Service duration | `55.227s` |
| Wall-clock duration | `55.745s` |
| Records per second | `18.11` |
| Segments per second | `36.21` |
| Batch count | 112 |
| Flush count | 4 |
| Retry count | 0 |
| Failure count | 0 |
| Checkpoint after completion | removed |
| Isolation | temporary positive `novelId`, cleaned from all Milvus collections |

- environment: Windows 11 Home Chinese Edition `10.0.26200`, i5-13500H, 12 physical cores / 16 logical processors, `15.73 GiB` visible memory, Java `17.0.12`, Maven `3.9.9`
- dependencies: local MySQL `8.0.46`, cloud Milvus, SiliconFlow `BAAI/bge-m3` with dimension `1024`
- configuration: batch size `18`, max retries `3`, retry backoff `1000ms`
- dataset: deterministic schema-aligned synthetic Chinese writing-memory corpus, five scenarios with `200` records each; generator is `scripts/generate-representative-import-dataset.ps1`
- evidence: `docs/benchmarks/import-benchmark-representative-live-20260811.json`
- interpretation: this is a bounded pinned-host operational baseline. It closes the source-controlled evidence task, but it is not a 50K production capacity claim because the corpus is synthetic and the host is a developer workstation.

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

The command prints the selected profile, dataset version, recall metrics, latency percentiles, and retrieved-context size. Use the default profile for the English CI fixture or `writing-zh-live-v1` for the Chinese production-like corpus. The committed live snapshot reports three sequential runs so cold-start and warmed-process latency are not conflated. It writes the complete response when `-OutputPath` is supplied. A live run requires a reachable MySQL database, Milvus collection, and configured embedding provider.

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
