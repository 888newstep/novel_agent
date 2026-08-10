# `novel_agent` Changelog For Interview

Updated: 2026-08-10

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
- evidence: `docs/BENCHMARK_REPORT.md`, `docs/WRITING_QUALITY_CASES.md`, `docs/METRICS_BASELINE.md`, `scripts/run-rag-evaluation.ps1`, and the 22-test Maven baseline

### 2026-08-10 (dependency security hardening)

- topic: dependency security and supply-chain maintenance
- problem: GitHub reported a high-severity dependency alert caused by old transitive components in the Milvus SDK graph
- change: added explicit Maven security baselines, upgraded the MySQL connector, enabled Dependabot for Maven and GitHub Actions, and added pull-request dependency review
- result: the resolved runtime tree no longer uses the identified old component versions, while the Milvus SDK API remains unchanged
- tradeoff: dependency overrides must be regression-tested when the SDK or transport stack changes
- evidence: `pom.xml`, `docs/DEPENDENCY_SECURITY.md`, `.github/dependabot.yml`, `.github/workflows/ci.yml`, and the full Maven test baseline
