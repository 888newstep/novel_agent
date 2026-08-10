# Cost Governance Case

Updated: 2026-08-10

## Goal

Show that `novel_agent` is not only able to generate text, but can also enforce budget limits, expose cost scopes, and degrade gracefully when generation or embedding paths fail.

## What Was Added

- per-request token guard
- daily and monthly token / cost guard
- per-novel daily token / cost guard
- per-model daily token / cost guard
- model-failure degradation: direct API retry, then outline-only fallback
- budget-block degradation: outline-only fallback for chapter generation
- embedding-failure degradation: local Ollama fallback and single-item retry path
- dashboard grouping by model and by novel
- degradation event records in the cost summary

## Actual Demo Scenario

### 1. Configure a strict per-novel budget

Example settings:

```yaml
ai:
  cost-control:
    strict-mode: true
    per-novel-daily-token-budget: 1500
    per-model-daily-token-budget: 5000
    degrade-on-budget-exceeded: true
```

### 2. Trigger chapter generation repeatedly

Call:

```http
POST /api/v1/novel/{novelId}/generate?topic=trial%20arc&style=hot-blooded&promptId=1A&currentChapterNum=10
```

Expected behavior after the novel-level budget is exhausted:

- request is not hard-crashed at the product layer
- generation falls back to `outline_only_response`
- response contains:
  - `degraded: true`
  - `degradationPolicy.trigger = budget_limit`
  - `degradationPolicy.strategy = outline_only_response`
- the returned content becomes a deterministic outline instead of a full model generation

## Why This Matters For Interviews

This case demonstrates four engineering points that are stronger than a plain "LLM app" story:

1. the system reasons about cost before calling the model
2. the system tracks consumption by request, novel, model, day, and month
3. the system does not fail blindly when the happy path is unavailable
4. the degraded response is still useful to the end user and demo-friendly

## Reproducible Before/After Benchmark

The repository includes a deterministic benchmark that exercises the existing `TokenCostService` without calling a real model or consuming external API quota.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-cost-governance-benchmark.ps1 `
  -OutputPath artifacts/cost-governance-benchmark.json
```

The benchmark replays four identical writing requests with a synthetic readable pricing fixture:

| Mode | Accepted | Blocked | Billable tokens | Estimated cost |
|------|----------|---------|-----------------|----------------|
| strict mode off | 4 | 0 | 40 | 4.0 |
| strict mode on, daily budget 20 | 2 | 2 | 20 | 2.0 |

- measured delta: `50%` fewer billable tokens and `50%` lower estimated cost
- operational behavior: the third and fourth requests are blocked before model execution
- deterministic evidence: `src/test/java/com/novel/agent/service/CostGovernanceBenchmarkTest.java` and `docs/benchmarks/cost-governance-benchmark-20260810.json`
- caveat: the prices are synthetic fixture values for behavior comparison, not a provider quotation

## Key Files

- `src/main/java/com/novel/agent/service/TokenCostService.java`
- `src/main/java/com/novel/agent/service/DeepSeekService.java`
- `src/main/java/com/novel/agent/service/EmbeddingService.java`
- `src/main/java/com/novel/agent/controller/NovelController.java`
- `src/test/java/com/novel/agent/service/TokenCostServiceTest.java`
- `src/test/java/com/novel/agent/controller/NovelControllerTest.java`
