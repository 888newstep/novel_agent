# Test Matrix

Updated: 2026-08-10

## Purpose

This document describes what is currently verified in `novel_agent`, how it is verified, and where the verification lives in the repository.
It is intentionally written as a GitHub-facing engineering document instead of an internal TODO.

## Current Test Baseline

- build entry: `mvn test -DskipITs`
- latest verified status: pass
- current automated test count: 27
- test types currently covered:
  - service-level unit tests
  - controller contract tests
  - cost-governance and degradation-path tests
  - import retry and progress-status tests

## Coverage Scope

| Area | What is verified | Evidence |
|------|------------------|----------|
| Retrieval evaluation | scenario-based evaluation report generation, stable comparison baseline, latency percentile fields | `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java` |
| Retrieval search | result merging, explanation output, chapter-aware filtering behavior, raw-score versus writing-default ranking case | `src/test/java/com/novel/agent/service/MilvusSearchServiceTest.java` |
| Writing generation response | `memoryLayers`, `consistencyCheck`, `generationTrace`, `postGenerationCheck` response contract, warn-to-pass regression gate | `src/test/java/com/novel/agent/controller/NovelControllerTest.java` |
| Budget degradation | budget block fallback to outline-only response | `src/test/java/com/novel/agent/controller/NovelControllerTest.java` |
| Cost governance | chat accounting, blocked requests, per-novel and per-model scopes, degradation event summary | `src/test/java/com/novel/agent/service/TokenCostServiceTest.java` |
| Cost governance benchmark | deterministic strict-mode before/after token and cost comparison | `src/test/java/com/novel/agent/service/CostGovernanceBenchmarkTest.java` |
| Cost-control API | summary, settings update error mapping, clear-record flow | `src/test/java/com/novel/agent/controller/CostControlControllerTest.java` |
| Import status API | enriched progress/status views and already-running import behavior | `src/test/java/com/novel/agent/controller/DataImportControllerTest.java` |
| Import retry hardening | batch retry success, retry exhaustion, retry cleanup semantics | `src/test/java/com/novel/agent/service/DataImportServiceTest.java` |
| Knowledge retrieval | basic external knowledge lookup contract | `src/test/java/com/novel/agent/service/KnowledgeSearchServiceTest.java` |

## Test Groups

### Service Tests

- `TokenCostServiceTest`
- `RagEvaluationServiceTest`
- `MilvusSearchServiceTest`
- `KnowledgeSearchServiceTest`
- `DataImportServiceTest`

Purpose:

- verify deterministic business logic without requiring full application startup
- keep optimization work safe while iterating on retrieval, import, and governance logic

### Controller Tests

- `NovelControllerTest`
- `CostControlControllerTest`
- `DataImportControllerTest`
- `RagEvaluationControllerTest`

Purpose:

- lock API response shape for interview demo flows
- protect GitHub-facing examples from accidental contract regressions

## What Is Not Yet Covered

The following areas still rely more on manual verification or environment-specific validation than on automated tests:

- real Milvus latency under cloud deployment conditions
- real embedding provider throughput and timeout behavior
- full end-to-end import throughput on a production-like dataset
- front-end cost dashboard rendering checks
- true provider-side token usage reconciliation when upstream APIs expose exact usage

## Recommended Verification Commands

### Full Regression

```powershell
mvn test -DskipITs
```

### Focused Retrieval and Generation Checks

```powershell
mvn "-Dtest=NovelControllerTest,MilvusSearchServiceTest,RagEvaluationServiceTest,RagEvaluationControllerTest" test
```

### Focused Import and Cost-Governance Checks

```powershell
mvn "-Dtest=DataImportServiceTest,DataImportControllerTest,TokenCostServiceTest,CostControlControllerTest" test
```

## Interview Interpretation

This matrix helps explain that `novel_agent` is not only ?feature complete?, but also has regression protection around the three SP-relevant axes:

1. retrieval quality
2. generation controllability
3. cost and stability governance
