# `novel_agent` Roadmap

Updated: 2026-08-11

## 1. Scope

`novel_agent` is the vertical product project.
It should prove that you can turn AI capabilities into a complete business system for novel writing, not that you can build another generic agent platform.

This repo should keep focusing on:

- structured novel knowledge modeling
- chapter-aware retrieval and writing memory assembly
- controlled generation and quality guardrails
- token cost governance
- offline evaluation and explainability
- reproducible open-source engineering quality

This repo should **not** absorb responsibilities already covered by `C:\Users\xiaohongfu\IdeaProjects\newagent`, such as:

- generic `ReAct` orchestration
- supervisor or multi-agent platform abstractions
- universal tool registry and tool protocol design
- general-purpose Adaptive RAG platform routing
- cross-domain knowledge platform capabilities

## 2. Current Base

The current repository already has a useful base:

- domain entities for novel, chapter, character, faction, event, item, skill, and inspiration
- retrieval services around `Milvus` and writing memory
- import pipeline and knowledge processing entry points
- token cost control endpoints and a lightweight dashboard
- RAG evaluation service and controller
- root-level open-source structure, CI, tests, and core docs

This means the next stage is not "add more technologies".
The next stage is "make the existing chain deeper, more measurable, more stable, and easier to present in interviews".

## 3. Resume Goal

For autumn recruitment SP, `novel_agent` should support three signals:

1. Business depth: you understand one vertical scenario deeply, not only generic AI frameworks.
2. Engineering rigor: you can quantify quality, cost, stability, and tradeoffs.
3. Open-source presentation: the repo can be cloned, read, started, tested, and explained clearly.

The best pairing is:

- `newagent`: generic AI platform capability, Adaptive RAG, ReAct, multi-agent, observability, evaluation framework
- `novel_agent`: vertical writing product, structured story memory, retrieval ranking, generation control, consistency guardrails, scenario-specific evaluation

## 4. Highest-Priority Gaps

### P0: clean positioning and documentation

Goal: make the repo easy to understand in 3 minutes.

- [x] add a dedicated document that explains how `novel_agent` complements `newagent`
- [x] add one architecture diagram for the vertical writing chain: import -> retrieval -> memory assembly -> generation -> validation -> evaluation
- [x] add one demo document with 2 to 3 real writing scenarios and expected outcomes
- [x] add one benchmark-style document that records retrieval quality and token cost before/after optimizations
- [x] ensure every core document is UTF-8 clean and readable in GitHub

### P1: strengthen vertical retrieval quality

Goal: make retrieval quality measurable and interview-friendly.

- [x] split evaluation cases by scenario:
  - character profile lookup
  - unresolved event recall
  - world setting lookup
  - item or skill lookup
  - pre-writing context assembly
- [x] extend `rag_eval_dataset.json` with more writing-specific hard cases
- [x] record `Recall@K`, `Precision@K`, `MRR`, keyword coverage, average latency, `P95`, and `P99`
- [x] expose retrieval explanation fields:
  - why a segment was recalled
  - which ranking signals contributed
  - whether chapter recency or unresolved-event weight changed the score
- [x] keep one stable evaluation profile so improvements can be compared over time

### P1: add generation quality guardrails

Goal: show that generation is controlled, not just "call model and print text".

- [x] add a pre-generation consistency check:
  - [x] conflicting character context and duplicate hook warnings
  - [x] resolved events being reused as unresolved memory
  - [x] item status conflicts
  - [x] faction relation conflict warnings
- [x] define a layered `WritingMemory` view:
  - recent chapter context
  - key characters
  - unresolved foreshadowing or events
  - current-scene world settings
- [x] add generation trace output for debugging:
  - selected memory blocks
  - dropped candidates
  - total context length
  - estimated and actual token cost
- [x] add post-generation rule checks for basic quality regression

### P1: harden import and cost-governance paths

Goal: show operational thinking under limited resources.

- [x] make import checkpoints explicit and observable
- [x] add idempotent retry strategy for batch import failures
- [x] add per-novel and per-stage progress reporting
- [x] expand token governance dimensions:
  - per request
  - per novel
  - daily
  - monthly
  - per model
- [x] add degradation policy when budget, model, or embedding service fails

### P2: improve observability and repo credibility

Goal: make the project look maintainable, not just functional.

- [x] add `docs/DEMO_SCRIPT.md` for live interview walkthrough
- [x] add `docs/CHANGELOG_FOR_INTERVIEW.md` to record important optimization rounds
- [x] add issue templates and pull request template for GitHub
- [x] add coverage reporting or at least a documented test matrix
- [x] add a small metrics document covering import throughput, retrieval latency, and token cost
- [x] pin vulnerable transitive dependencies and automate pull-request dependency review

## 5. Concrete Deliverables For SP

Before you treat this repo as a main SP-facing project, it should have these deliverables:

- [x] one clean GitHub homepage with clear value proposition
- [x] one reproducible local startup path
- [x] one evaluation report with numbers, not only descriptions
- [x] one retrieval optimization case with before/after comparison
- [x] one generation consistency case with a visible quality gain
- [x] one cost governance case with an actual limit or degradation demonstration
- [x] one end-to-end demo script that can be explained in 5 minutes

## 6. Implementation Order

Do not push these in parallel without sequence.
The efficient order is:

1. finish doc cleanup and repo positioning
2. expand evaluation dataset and fixed metrics report
3. add retrieval explanation and generation guardrails
4. harden import stability and cost governance
5. add demo scripts and benchmark evidence for interviews

## 7. Resume Mapping

If this roadmap is completed well, `novel_agent` can support resume statements like:

- designed structured story knowledge and multi-collection retrieval for novel writing
- built writing-memory assembly and controllable generation guardrails
- established offline evaluation and token-cost linked analysis
- improved retrieval relevance and explainability with scenario-specific ranking signals
- hardened import pipeline and budget governance for long-text AI workloads

## 8. Definition Of Done

`novel_agent` is ready for strong resume usage when:

- [x] the project can be cloned and verified by others without oral explanation
- [x] the repo shows clear boundaries from `newagent`
- [x] retrieval improvements have measurable evidence
- [x] generation quality control has at least one convincing case
- [x] cost and stability mechanisms can be demonstrated
- [x] the GitHub docs support interview storytelling directly

The source-controlled definition of done is complete. Live Milvus throughput now has both an isolated operational baseline and a pinned-host, schema-aligned representative baseline. These results remain bounded operational evidence and are not a 50K production capacity claim.

## 9. Maintenance Queue

The P0/P1/P2 source-controlled plan is complete. The following items are intentionally kept as the next evidence-oriented queue rather than mixed into the completed roadmap:

- [x] record an isolated live import throughput baseline without modifying the shared corpus
- [x] record a deterministic token-cost before/after comparison using the existing governance service
- [x] add a corpus-aligned Chinese RAG evaluation profile and isolate its history from the English fixture
- [x] run the aligned Chinese profile against reachable Milvus and publish a bounded three-run semantic quality baseline
- [x] repeat the import benchmark at a larger operational scale with novelId isolation and cleanup
- [x] repeat the import benchmark on a pinned host specification and schema-aligned representative corpus; keep real-corpus and 50K capacity claims explicitly pending
- [x] persist aggregate RAG evaluation snapshots in MySQL with an in-memory fallback and history API
- [x] add Prometheus time-series export for operational dashboards after the MySQL history contract stabilizes
