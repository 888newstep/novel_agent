# `novel_agent` Changelog For Interview

Updated: 2026-08-11

## Purpose

This document records the most important optimization rounds in a way that is easy to explain during interviews.
It should capture problem, change, result, and tradeoff instead of raw commit history.

## Entry Template

- date: when the change was completed
- topic: retrieval, generation, import, cost, or evaluation
- problem: what concrete issue existed before the change
- change: what logic or structure was adjusted
- result: what improved after the change
- tradeoff: what cost, latency, or complexity increased
- evidence: which report, case, or metric supports the result

## Current Recorded Rounds

### 2026-08-11 (local/cloud environment preflight)

- topic: open-source startup reproducibility and dependency-boundary clarity
- problem: the repository documented MySQL and Milvus but did not provide a safe connectivity preflight; the benchmark table also retained a transient `pending/blocked` profile row after live evidence had been recorded separately
- change: added `scripts/check-infrastructure.ps1` and `docs/ENVIRONMENT_RUNBOOK.md`; required checks cover MySQL, Milvus, and the embedding endpoint, while Redis and RabbitMQ are explicitly optional because they are not on the current import/retrieval critical path; corrected the stale profile row
- result: a new clone can verify local/cloud reachability without printing credentials, and interview readers can distinguish network reachability from authentication, schema, and semantic validation
- tradeoff: the preflight cannot prove MySQL credentials, Milvus collection readiness, embedding model authorization, or RabbitMQ/Redis business correctness; those remain application-backed checks
- evidence: `scripts/check-infrastructure.ps1`, `docs/ENVIRONMENT_RUNBOOK.md`, `docs/benchmarks/infrastructure-preflight-live-20260811.json`, `README.md`, and `docs/BENCHMARK_REPORT.md`

### 2026-08-08

- topic: repository structure and open-source baseline
- problem: the repository previously used a nested project layout and contained local-only noise
- change: flattened the project to the repository root, added root docs, CI, and open-source metadata
- result: the repo became easier to clone, read, build, and present on GitHub
- tradeoff: none at runtime; only one-time repository restructuring effort
- evidence: root `README.md`, `docs/ARCHITECTURE.md`, CI workflow, and successful test execution

### 2026-08-09

- topic: SP-facing positioning and demo materials
- problem: the project lacked direct interview-facing documents and its boundary with `newagent` was not explicit enough
- change: added roadmap, positioning, demo script, benchmark template, and quality case documents
- result: the repository now better supports five-minute walkthroughs and resume storytelling
- tradeoff: documentation baseline improved first; code-level quality upgrades still need follow-up implementation
- evidence: `docs/SP_POSITIONING.md`, `docs/DEMO_SCRIPT.md`, `docs/BENCHMARK_REPORT.md`, and `docs/WRITING_QUALITY_CASES.md`
### 2026-08-09 (evaluation upgrade)

- topic: scenario-based retrieval evaluation
- problem: the evaluation dataset was not aligned with the five writing scenarios in the roadmap, and the report did not expose `P95` latency
- change: rewrote the evaluation datasets into five writing-specific categories and extended the evaluation report model with `P95` latency
- result: the project now better supports scenario-based benchmarking, stable profile comparison, and interview discussion around latency percentiles
- tradeoff: tests needed constructor and assertion updates to match the new report shape
- evidence: `src/main/resources/rag_eval_dataset.json`, `src/test/resources/rag_eval_dataset.json`, `src/main/java/com/novel/agent/service/RagEvaluationService.java`, and `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java`

### 2026-08-09 (retrieval explanation + generation guardrails)

- topic: retrieval explainability and generation consistency guardrails
- problem: retrieval results lacked interview-friendly score explanations, and generation endpoints did not expose layered memory or pre-check signals
- change: added explanation output in retrieval results, added layered `WritingMemory` summaries, and returned `consistencyCheck` warnings from preview and generation endpoints
- result: the project now exposes why memory was selected, highlights future-chapter leaks and relation conflicts, and is easier to demo as a controlled writing system instead of a plain LLM wrapper
- tradeoff: API response payloads became richer and required extra controller tests to keep response contracts stable
- evidence: `src/main/java/com/novel/agent/service/MilvusSearchService.java`, `src/main/java/com/novel/agent/controller/NovelController.java`, and `src/test/java/com/novel/agent/controller/NovelControllerTest.java`
- profile: writing-default-v1 (datasetVersion 2026-08-09, 15 scenario-aligned cases, default comparison baseline)
### 2026-08-09 (generation trace and token observability)

- topic: generation debugging and cost observability
- problem: generation endpoints returned content and memory summaries, but did not explain which memory blocks were actually used or how prompt size translated into token cost
- change: added `generationTrace` output with selected memory blocks, dropped candidate counts, context length stats, pre-call token reservation estimates, and post-call usage observations from `TokenCostService`
- result: the writing chain is now easier to debug, benchmark, and explain in interviews as a controllable generation pipeline rather than a plain model call
- tradeoff: response payloads are larger, and recorded post-call usage still follows the current service-side estimation mode unless the upstream provider exposes exact usage
- evidence: `src/main/java/com/novel/agent/controller/NovelController.java` and `src/test/java/com/novel/agent/controller/NovelControllerTest.java`
### 2026-08-09 (post-generation regression checks)

- topic: generation quality guardrails
- problem: generation results had no lightweight rule-based regression screen after the model returned text
- change: added `postGenerationCheck` output to flag empty output, too-short content, repeated openings, excessive ellipsis, and banned transition phrases
- result: the API now returns both pre-generation and post-generation quality signals, making the writing chain easier to debug and easier to explain as a controlled system
- tradeoff: the checks are heuristic and conservative; they are meant for regression hints rather than final literary judgment
- evidence: `src/main/java/com/novel/agent/controller/NovelController.java` and `src/test/java/com/novel/agent/controller/NovelControllerTest.java`
### 2026-08-09 (resolved event and item-state consistency checks)

- topic: pre-generation consistency guardrails
- problem: the pre-generation check could catch duplicate context and relation conflicts, but it did not verify whether resolved plot hooks or future item state changes were leaking into current writing memory
- change: extended `consistencyCheck` to validate hook ids against MySQL event resolution state and validate item memory against authoritative artifact/skill records plus item mutation logs
- result: the writing pipeline now flags resolved-event reuse, future item first-appearance leaks, inactive item states, and future item mutations before generation starts
- tradeoff: the check now depends on more repository lookups, so it adds a small amount of synchronous validation work to generation requests
- evidence: `src/main/java/com/novel/agent/controller/NovelController.java` and `src/test/java/com/novel/agent/controller/NovelControllerTest.java`
### 2026-08-10 (GitHub homepage and reproducible smoke entrypoint)

- topic: open-source presentation and local verification
- problem: the repository had strong engineering documents, but the GitHub landing page did not yet connect the product value proposition, five-minute flow, key endpoints, verification baseline, and current limitations in one place
- change: reorganized `README.md`, added a key endpoint table and evidence matrix, and added `scripts/smoke-test.ps1` for health and cost-summary checks against a running instance
- result: a reviewer can understand the product boundary, run the project, verify the basic service surface, and find interview evidence without reading the source tree first
- tradeoff: the smoke script validates service availability and cost summary only; real Milvus and model behavior still requires configured external dependencies
- evidence: `README.md`, `scripts/smoke-test.ps1`, `docs/DEMO_SCRIPT.md`, and `docs/ROADMAP.md`

### 2026-08-09 (test matrix and metrics baseline docs)

- topic: repository credibility and observability evidence
- problem: the repository had tests and benchmark-oriented code paths, but GitHub readers still lacked one concise matrix of what is verified and one honest baseline document describing what is already measured versus what still needs a real benchmark run
- change: added `docs/TEST_MATRIX.md` and `docs/METRICS_BASELINE.md`, then linked them from the README and roadmap
- result: the repository now looks more maintainable and interview-ready because verification scope and metric maturity are explicit instead of implied
- tradeoff: the metrics baseline is intentionally conservative and leaves some public numbers marked as pending until a real environment-backed run is recorded
- evidence: `docs/TEST_MATRIX.md`, `docs/METRICS_BASELINE.md`, `README.md`, and `docs/ROADMAP.md`

### 2026-08-09 (cost governance dimensions and degradation policy)

- topic: cost governance and graceful degradation
- problem: cost control existed at a basic global level, but the repo could not yet explain per-novel or per-model governance, nor show a convincing fallback policy when budget or AI dependencies failed
- change: expanded `TokenCostService` with per-novel and per-model scopes, dashboard breakdowns, degradation event recording, and configurable degradation flags; added model direct-call fallback, budget-block outline fallback, and embedding Ollama fallback paths
- result: the project now demonstrates multi-scope token governance and practical degradation behavior instead of a binary success/fail model path
- tradeoff: response payloads and cost settings became richer, and degraded paths add extra branching that needs tests and explanation in docs
- evidence: `src/main/java/com/novel/agent/service/TokenCostService.java`, `src/main/java/com/novel/agent/service/DeepSeekService.java`, `src/main/java/com/novel/agent/service/EmbeddingService.java`, `src/main/java/com/novel/agent/controller/NovelController.java`, `src/test/java/com/novel/agent/service/TokenCostServiceTest.java`, `src/test/java/com/novel/agent/controller/NovelControllerTest.java`, and `docs/COST_GOVERNANCE_CASE.md`

### 2026-08-09 (import batch retry hardening)

- topic: import reliability under partial batch failure
- problem: training-data import could resume from checkpoints, but a Milvus batch insert failure could still leave the current chapter range partially written and was not safe to retry in place
- change: added configurable batch retries with backoff, retry status fields, range cleanup for `novel_id == 0` training batches before retry, and failure propagation when retry budget is exhausted; added unit tests for both retry-success and retry-exhausted flows
- result: the import pipeline now supports effectively idempotent retries for training-data chapter ranges and exposes clearer operational signals for demo and debugging
- tradeoff: a retry now performs an extra Milvus delete for the affected chapter range, and a fully exhausted batch will fail the import instead of being silently skipped
- evidence: `src/main/java/com/novel/agent/service/DataImportService.java` and `src/test/java/com/novel/agent/service/DataImportServiceTest.java`

### 2026-08-09 (import checkpoints and progress observability)

- topic: import pipeline reliability and observability
- problem: the import pipeline had resume capability, but its checkpoints, current stage, flush progress, and failure context were not explicit enough for GitHub users or interview demos
- change: rewrote the import service state model to expose stage-based status snapshots, checkpoint paths, resume offsets, batch counts, flush counts, progress percentage, and failure messages; added `/api/import/status` as an alias of the enriched progress view
- result: the 50K import pipeline is now much easier to observe, resume, and explain as an engineering system under constrained resources rather than a black-box script
- tradeoff: the service now maintains a richer in-memory status snapshot and slightly more bookkeeping during batch processing
- evidence: `src/main/java/com/novel/agent/service/DataImportService.java`, `src/main/java/com/novel/agent/controller/DataImportController.java`, and `src/test/java/com/novel/agent/controller/DataImportControllerTest.java`

### 2026-08-10 (reproducible evidence and document hygiene)

- topic: benchmark reproducibility and SP-facing evidence
- problem: the repository described retrieval and generation guardrails, but the before/after evidence was not yet connected to runnable commands and a few core Markdown files still carried a BOM
- change: added context-size metrics to the RAG report, added `scripts/run-rag-evaluation.ps1`, recorded a deterministic ranking case and post-generation regression case, and normalized core Markdown files to UTF-8 without BOM
- result: GitHub readers can distinguish CI fixture evidence from live Milvus numbers, reproduce the API-backed evaluation command, and follow measurable retrieval and generation cases without oral context
- tradeoff: fixture results remain explicitly non-production evidence; live latency and throughput still require a reachable environment
- evidence: `docs/BENCHMARK_REPORT.md`, `docs/WRITING_QUALITY_CASES.md`, `docs/METRICS_BASELINE.md`, `scripts/run-rag-evaluation.ps1`, and the 27-test Maven baseline

### 2026-08-10 (corpus-aligned RAG evaluation profiles)

- topic: semantic evaluation validity across production-like corpora
- problem: the existing English fixture and the live Chinese corpus shared one history, so a profile switch could create a misleading comparison
- change: added `writing-zh-live-v1` with 15 Chinese cases, profile-aware dataset loading, independent report history, explicit empty-report reasons, `/profiles` metadata, and `-Profile` support in `scripts/run-rag-evaluation.ps1`
- result: the default `writing-default-v1` contract remains backward compatible; the new Chinese dataset loads in CI and the service/controller test suite reaches 31 passing tests
- live validation: the first attempt on 2026-08-10 was blocked by Milvus TCP reachability; after the endpoint recovered, three sequential read-only runs with `novelId=0` and `TopK=5` produced Recall@5 `73.3%`, Precision@5 `38.7%–40.0%`, MRR `0.667–0.700`, and a mean latency of `111.6ms`; no vectors were written
- evidence: `src/main/resources/rag_eval_dataset_zh.json`, `src/main/java/com/novel/agent/service/RagEvaluationService.java`, `src/main/java/com/novel/agent/controller/RagEvaluationController.java`, and `docs/benchmarks/rag-evaluation-zh-live-20260810.json`
### 2026-08-10 (live Milvus validation and collection lifecycle hardening)

- topic: real-environment retrieval reliability
- problem: the application could return `500` when a Milvus collection was not loaded, and batch loading stopped at the first collection without an index
- change: added startup auto-load for finished indexes, isolated load failures per collection, added an empty-result degradation path for unavailable search collections, and made vector cleanup best effort across unready collections
- result: the application starts with local MySQL, cloud Milvus, and SiliconFlow embedding enabled; the live event path returned the expected unresolved hook with ranking explanations, and cleanup completed without leaving test novels in MySQL
- evidence: `src/main/java/com/novel/agent/config/MilvusLifecycleConfig.java`, `src/main/java/com/novel/agent/service/MilvusAdminService.java`, `src/main/java/com/novel/agent/service/MilvusSearchService.java`, and `src/test/java/com/novel/agent/service/MilvusSearchServiceTest.java`
- live run: 15 RAG queries returned 5 candidates each with `Avg=256.1ms`, `P95=692ms`, and `P99=692ms`; semantic scores remain explicitly excluded because the cloud corpus is not aligned with the fixed evaluation labels

### 2026-08-10 (dependency security hardening)

- topic: dependency security and supply-chain maintenance
- problem: GitHub reported a high-severity dependency alert caused by old transitive components in the Milvus SDK graph
- change: upgraded `milvus-sdk-java` from `2.4.11` to `2.6.23`, pinned Jackson, Netty, OpenNLP, Jsoup, Apache Commons, and MySQL security baselines, enabled Dependabot for Maven and GitHub Actions, and added pull-request dependency review
- result: the resolved runtime graph is materially smaller, the identified old component versions are no longer selected, and the full test suite remains green
- tradeoff: the SDK and transport stack now have an explicit upgrade boundary that must be regression-tested together
- evidence: `pom.xml`, `docs/DEPENDENCY_SECURITY.md`, `.github/dependabot.yml`, `.github/workflows/ci.yml`, and the full Maven test baseline

### 2026-08-10 (isolated import benchmark and novel-scoped checkpoints)

- topic: import throughput evidence and data isolation
- problem: the import benchmark path wrote every training segment to `novel_id=0`, so a live throughput run could pollute the shared corpus and retry cleanup was not novel-scoped
- change: added an explicit `novelId` import overload, novel-aware segment rows, novel-aware retry cleanup, isolated checkpoint suffixes, enriched status metrics, and `scripts/run-import-benchmark.ps1`
- result: a live 60-record run produced 120 segments in `8.176s` service time at `7.34 records/s` and `14.68 segments/s`, with `0` retries, `1` flush, and `0` failures; the temporary id was deleted from all Milvus collections afterward
- tradeoff: the benchmark uses a small operational sample and must not be presented as a 50K capacity claim; larger runs still need the same cleanup discipline
- evidence: `src/main/java/com/novel/agent/service/DataImportService.java`, `src/main/java/com/novel/agent/controller/DataImportController.java`, `src/test/java/com/novel/agent/service/DataImportServiceTest.java`, `scripts/run-import-benchmark.ps1`, and `docs/BENCHMARK_REPORT.md`

### 2026-08-10 (larger isolated import benchmark)

- topic: larger-scale import throughput evidence
- problem: the first 60-record run proved isolation and cleanup but was too small to show behavior beyond a smoke-sized sample
- change: replayed the existing JSONL import contract with 600 generated records using temporary positive `novelId` isolation and automatic cleanup
- result: 600 records produced 1200 segments in `37.853s` service time at `15.85 records/s` and `31.7 segments/s`, with `0` retries, `0` failures, `3` flushes, and no remaining checkpoint
- tradeoff: this is stronger operational evidence but still not a capacity claim; the next run must pin host resources and use a representative corpus
- evidence: `scripts/run-import-benchmark.ps1`, `docs/benchmarks/import-benchmark-large-live-20260810.json`, and `docs/BENCHMARK_REPORT.md`

### 2026-08-10 (deterministic token-cost governance evidence)

- topic: cost governance measurement
- problem: token limits and degradation behavior were implemented, but the repository did not yet show a reproducible before/after cost comparison
- change: added `CostGovernanceBenchmarkTest`, a Maven-backed PowerShell runner, and a committed JSON evidence snapshot using the existing `TokenCostService`
- result: with four identical writing requests, strict governance accepted `2/4`, blocked `2/4`, and reduced billable tokens and estimated cost by `50%`
- tradeoff: the benchmark uses synthetic pricing to validate control behavior; it is not a real provider billing quote
- evidence: `src/test/java/com/novel/agent/service/CostGovernanceBenchmarkTest.java`, `scripts/run-cost-governance-benchmark.ps1`, `docs/COST_GOVERNANCE_CASE.md`, and `docs/benchmarks/cost-governance-benchmark-20260810.json`

### 2026-08-10 (aggregate RAG evaluation history)

- topic: durable evaluation evidence without persisting novel content
- problem: RAG reports were useful in the current process, but a restart removed trend history and a full report would be too large and privacy-sensitive to persist by default
- change: added a MySQL aggregate snapshot entity/repository, an incremental migration, profile-plus-novel isolation, bounded history/report APIs, startup restoration, and best-effort database degradation
- result: a live Chinese evaluation recorded Recall@5 `80.0%`, Precision@5 `41.3333%`, MRR `0.7556`, average latency `307.733ms`, and `P95/P99=887ms`; one snapshot row was restored after restart while query details remained absent
- tradeoff: history stores scalar aggregates only; Prometheus time-series export and pinned-host capacity measurement remain separate follow-up work
- evidence: `src/main/java/com/novel/agent/entity/RagEvaluationSnapshot.java`, `src/main/java/com/novel/agent/repository/RagEvaluationSnapshotRepository.java`, `src/main/java/com/novel/agent/service/RagEvaluationService.java`, `sql/migrations/V20260810__add_rag_evaluation_snapshots.sql`, `docs/RAG_EVALUATION_HISTORY.md`, and `docs/benchmarks/rag-evaluation-history-live-20260810.json`

### 2026-08-11 (Prometheus operational metrics)

- topic: operational observability for RAG quality and latency
- problem: MySQL history made aggregate reports durable, but dashboards still had no standard time-series scrape contract for completed evaluations, query latency, skips, or persistence failures
- change: added Spring Boot Actuator, Micrometer Prometheus export, profile-scoped RAG counters/timers/latest gauges, bounded skip reasons, restart restoration of latest gauges, and metric contract tests
- result: `GET /actuator/prometheus` now exposes dashboard-ready RAG signals without putting `novelId`, query text, retrieved content, or credentials into metric labels
- tradeoff: counters and timers are process-local and reset on JVM restart; Prometheus must scrape the endpoint for durable time-series history, while MySQL restores latest gauges
- evidence: `src/main/java/com/novel/agent/service/RagEvaluationMetrics.java`, `src/test/java/com/novel/agent/service/RagEvaluationMetricsTest.java`, `docs/OBSERVABILITY.md`, and `src/main/resources/application.yml`

### 2026-08-11 (pinned-host representative import benchmark)

- topic: bounded import throughput evidence on a recorded host
- problem: the previous 600-record run proved isolation and cleanup but did not record a host specification or balanced writing-domain corpus
- change: added a deterministic five-scenario Chinese writing-memory corpus generator and replayed 1,000 records through the isolated import endpoint with temporary `novelId` cleanup
- result: 1,000 records produced 2,000 segments in `55.227s` service time at `18.11 records/s` and `36.21 segments/s`, with `0` retries, `0` failures, `4` flushes, and successful cleanup
- tradeoff: the corpus is schema-aligned synthetic data and the host is a developer workstation; the result is a bounded operational baseline, not a 50K production capacity claim
- evidence: `scripts/generate-representative-import-dataset.ps1`, `scripts/run-import-benchmark.ps1`, `docs/benchmarks/import-benchmark-representative-live-20260811.json`, and `docs/BENCHMARK_REPORT.md`

### 2026-08-27 (Chinese RAG re-baseline after retrieval streamline)

- topic: current retrieval baseline vs the 2026-08-10 recorded 73.3%
- problem: the committed report still showed Recall@5 `73.3%`, but the retrieval streamline commits (`7937926`, `74ec537`) had changed ranking behavior and the persisted baseline was stale
- change: re-ran the same `writing-zh-live-v1` evaluation three times (`novelId=0`, `TopK=5`, 15 queries) and committed a new snapshot `rag-evaluation-zh-live-20260827.json`
- result: warmed stable-state Recall@5 `86.7%` (runs 2-3 identical); three-run mean `88.9%`; Precision@5 `45.3%`; MRR `0.806`; keyword coverage `81.5%`; warmed latency `82.5ms` / `P95=131ms` (cold first run had a 48s P95 warm-up spike)
- scenario signal: `unresolved_event` recovered from `33.3%` to `100%`; `character_profile` is now the weakest bucket at `66.7%` (`少宫主`, `萧师兄`)
- root causes: `少宫主` has a single relevant segment that stays below the vector candidate pool; `萧师兄` lacks Chinese tokenization in keyword extraction so the exact keyword never matches corpus text
- tradeoff: this is a 15-query operational baseline, not a capacity claim; the character_profile bucket is the next tuning target if the number needs to rise above 86.7%
- evidence: `docs/benchmarks/rag-evaluation-zh-live-20260827.json`, `docs/BENCHMARK_REPORT.md`, `docs/METRICS_BASELINE.md`
