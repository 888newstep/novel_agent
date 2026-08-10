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
| R0-LIVE | pending | pending | Milvus-backed baseline | pending | pending | pending | pending | pending | pending | pending | pending | pending | run `scripts/run-rag-evaluation.ps1` |

`R0-CONTRACT` and `R1-FIXTURE` are deterministic CI evidence, not production latency claims. A live row must be recorded only after the embedding provider, Milvus instance, host, dataset, and parameters are written down.

## Reproducible Live Run

The API-backed runner keeps the benchmark command out of oral explanations:

```powershell
pwsh -File scripts/run-rag-evaluation.ps1 `
  -BaseUrl http://localhost:8080 `
  -NovelId 0 `
  -TopK 5 `
  -OutputPath artifacts/rag-report.json
```

The command prints the stable profile, dataset version, recall metrics, latency percentiles, and retrieved-context size. It writes the complete response when `-OutputPath` is supplied. A live run requires a reachable MySQL database, Milvus collection, and configured embedding provider.

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
