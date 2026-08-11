# Novel Agent

> A vertical AI writing system for long-form web novels: structured story knowledge, chapter-aware retrieval, controlled generation, measurable evaluation, and cost-aware degradation.

[![CI](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

## Why This Project

`novel_agent` focuses on one complete business scenario instead of trying to be another generic agent platform. It turns fragmented story data into writing memory, uses that memory to support chapter generation, and exposes evaluation, cost, and quality signals for debugging and interviews.

The generic AI platform work stays in the separate `newagent` repository:

- `newagent`: reusable agent infrastructure and platform abstractions
- `novel_agent`: vertical novel-writing product and domain-specific engineering depth

## Product Chain

```text
Story data -> structured knowledge -> chapter-aware retrieval
           -> writing memory -> consistency checks -> generation
           -> post-generation checks -> evaluation and cost observability
```

## Five-Minute Demo

1. Verify `GET /api/v1/novel/health`.
2. Create or select a novel through `/api/v1/novel`.
3. Import data with `POST /api/import/training-data` and observe `/api/import/progress`.
   For an isolated benchmark, use `POST /api/import/training-data/{novelId}` and pass `filePath`.
4. Preview memory with `GET /api/v1/novel/{novelId}/memory`.
5. Generate with `POST /api/v1/novel/{novelId}/generate`.
6. Inspect `memoryLayers`, `consistencyCheck`, `generationTrace`, `postGenerationCheck`, and `degradationPolicy`.
7. Run evaluation through `POST /api/v1/novel/evaluate/segments`; use `profile=writing-zh-live-v1` for the Chinese production-like corpus.
8. Inspect persisted aggregate history with `GET /api/v1/novel/evaluate/history?novelId=0&profile=writing-zh-live-v1`.
9. Inspect cost scopes with `GET /api/admin/cost/summary`.

See [`docs/DEMO_SCRIPT.md`](docs/DEMO_SCRIPT.md) for the complete interview walkthrough.

## Features

- Structured domain modeling for novels, chapters, characters, factions, skills, artifacts, events, and inspiration.
- Retrieval-augmented writing memory with Milvus vector search and chapter-aware filtering.
- Chapter generation orchestration built around DeepSeek prompts and retrieved context.
- Token cost governance with budget limits, usage history, and a lightweight dashboard.
- RAG evaluation workflows for recall, precision, MRR, keyword coverage, and latency.
- Import checkpoints, progress snapshots, retry cleanup, and failure propagation for large datasets.
- Per-request, per-novel, per-model, daily, and monthly token governance with useful degraded responses.

## Engineering Evidence

| Area | Current capability | Evidence |
|------|--------------------|----------|
| Retrieval quality | Writing-specific dataset with `Recall@K`, `Precision@K`, `MRR`, context size, `P95`, and `P99` | `docs/BENCHMARK_REPORT.md` |
| Generation control | Layered memory, consistency warnings, trace output, and post-generation checks | `src/main/java/com/novel/agent/controller/NovelController.java` |
| Import stability | Streaming import, novel-scoped checkpoints, progress reporting, and idempotent retries | `src/main/java/com/novel/agent/service/DataImportService.java` |
| Cost governance | Scoped budgets, degradation events, model fallback, and outline fallback | `src/main/java/com/novel/agent/service/TokenCostService.java` |
| Evaluation history | MySQL aggregate snapshots with in-memory fallback; query details and novel text are not persisted | `src/main/java/com/novel/agent/service/RagEvaluationService.java` |
| Dependency hygiene | Security-fixed transitive baselines, Dependabot, and PR dependency review | `docs/DEPENDENCY_SECURITY.md` |
| Regression safety | 35 automated service and controller contract tests | `docs/TEST_MATRIX.md` |

## Tech Stack

- Java 17
- Spring Boot 3.5
- Spring Web, WebFlux, Validation, JPA
- MySQL
- Milvus
- LangChain4j
- DeepSeek API
- spring-dotenv

## Repository Layout

```text
.github/workflows/ci.yml   GitHub Actions CI
.env.example               Local environment template
pom.xml                    Maven project definition
sql/init.sql               Database bootstrap script
sql/migrations/            Incremental database migrations
src/main                   Application source and resources
src/test                   Tests
scripts/smoke-test.ps1      Local health and cost smoke check
scripts/run-rag-evaluation.ps1  Reproducible API-backed RAG report
docs/PROJECT_DETAILS.md    Product details and API examples
docs/DATAFLOW.md           Data flow notes
docs/ROADMAP.md            Project roadmap
docs/SP_POSITIONING.md     Resume and repository positioning
docs/DEMO_SCRIPT.md        Interview walkthrough script
docs/BENCHMARK_REPORT.md   Retrieval and cost benchmark record
docs/WRITING_QUALITY_CASES.md  Writing consistency case studies
docs/ARCHITECTURE.md       Repository structure notes
docs/TEST_MATRIX.md        Automated verification matrix
docs/METRICS_BASELINE.md   Public metrics baseline and evidence status
docs/COST_GOVERNANCE_CASE.md Cost-governance demo case
docs/RAG_EVALUATION_HISTORY.md Aggregate evaluation history and restart behavior
```

## Quick Start

### Prerequisites

- JDK 17
- Maven 3.9+
- MySQL 8.x
- Milvus 2.x
- a configured model provider
- Ollama or SiliconFlow embedding configuration when retrieval/import is enabled

### Clone and Configure

Use the repository clone command shown above, then run:

```powershell
Copy-Item .env.example .env
```

Edit `.env` with local database, Milvus, model, and embedding settings. Secrets are intentionally not committed.

### Verify and Run

```powershell
mvn test -DskipITs
mvn spring-boot:run
```

In another PowerShell window, run the reproducible smoke check:

```powershell
./scripts/smoke-test.ps1
```

The default application address is `http://localhost:8080`.

## Key Endpoints

| Capability | Endpoint |
|------------|----------|
| Health check | `GET /api/v1/novel/health` |
| Chapter generation | `POST /api/v1/novel/{novelId}/generate` |
| Memory preview | `GET /api/v1/novel/{novelId}/memory` |
| Retrieval search | `POST /api/v1/novel/{novelId}/search` |
| Training-data import | `POST /api/import/training-data` |
| Isolated training-data import | `POST /api/import/training-data/{novelId}` |
| Import progress | `GET /api/import/progress` |
| RAG evaluation | `POST /api/v1/novel/evaluate/segments`; `GET /api/v1/novel/evaluate/profiles` |
| RAG report/history | `GET /api/v1/novel/evaluate/report`; `GET /api/v1/novel/evaluate/history` |
| Cost summary | `GET /api/admin/cost/summary` |
| Cost dashboard | `GET /cost-panel` |

## Documents

- `docs/PROJECT_DETAILS.md`
- `docs/DATAFLOW.md`
- `docs/ROADMAP.md`
- `docs/SP_POSITIONING.md`
- `docs/DEMO_SCRIPT.md`
- `docs/BENCHMARK_REPORT.md`
- `docs/WRITING_QUALITY_CASES.md`
- `docs/ARCHITECTURE.md`
- `docs/TEST_MATRIX.md`
- `docs/METRICS_BASELINE.md`
- `docs/COST_GOVERNANCE_CASE.md`
- `docs/RAG_EVALUATION_HISTORY.md`
- `docs/DEPENDENCY_SECURITY.md`
- `CONTRIBUTING.md`
- `SECURITY.md`

## Verification Baseline

The current repository includes 35 automated tests covering retrieval, generation response contracts, cost governance, degradation behavior, import retries, evaluation history, and controller APIs.

```powershell
mvn test -DskipITs
```

See [`docs/TEST_MATRIX.md`](docs/TEST_MATRIX.md) for the exact test scope and environment-dependent gaps.

For an environment-backed retrieval report, run `scripts/run-rag-evaluation.ps1` after MySQL, Milvus, and the embedding provider are available. Evaluation writes only aggregate metrics to MySQL; `GET /api/v1/novel/evaluate/history` can read them after an application restart.

## Current Limitations

- Larger Milvus throughput and capacity claims still require an environment-backed benchmark run; the 15-query Chinese retrieval baseline is recorded in `docs/BENCHMARK_REPORT.md`.
- Exact provider-side token usage depends on whether the upstream model API returns usage metadata.
- Outline-only degradation preserves product usability but is not a substitute for full literary generation.
- Generic agent orchestration remains outside this repository by design.

## License

Apache License 2.0
