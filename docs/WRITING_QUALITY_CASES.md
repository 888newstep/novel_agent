# `novel_agent` Writing Quality Cases

Updated: 2026-08-10

## Purpose

This document stores before-and-after cases for writing quality control.
It should prove that the system improves consistency and controllability, not only retrieval metrics.

## Case Format

Each case should contain:

- scenario background
- target chapter or writing task
- relevant characters, events, factions, items, or settings
- retrieval or memory behavior before optimization
- retrieval or memory behavior after optimization
- generation difference
- final engineering conclusion

## Case A

Problem: the generation step may ignore unresolved hooks from previous chapters if retrieval only favors semantic similarity.

Expected optimization direction:

- increase unresolved-event signal in ranking
- expose explanation fields for why unresolved hooks were included
- verify that continuation memory includes the unresolved event before generation

Success standard:

- unresolved event appears in writing memory
- generation references the hook without contradicting resolved history
- latency and token growth remain acceptable

## Case B

Problem: a key character may act inconsistently if current-scene retrieval misses stable profile information.

Expected optimization direction:

- add stronger character profile recall rules
- prioritize main character traits in writing memory assembly
- check whether generated text conflicts with known profile fields

Success standard:

- key character profile is recalled for continuation writing
- generated behavior does not contradict stored traits
- story continuity improves in qualitative review

## Case C

Problem: long-form writing can lose track of faction relations and item state transitions.

Expected optimization direction:

- add scenario-specific filters for faction and item memory
- validate whether already-consumed or resolved item states are reintroduced incorrectly
- add a generation review checklist for relation and state conflicts

Success standard:

- faction relations match stored context
- item status remains logically consistent
- the generation step has fewer obvious continuity errors

## Recorded Case 1: Continuation Ranking

Scenario: continue a trial arc at chapter `10` for the query `dragon oath`.

- before optimization: raw vector score returns a chapter-8 distractor (`0.95`) ahead of the relevant segment (`0.92`); Top-1 hit is `0/1`
- after optimization: `writing-default-v1` promotes the relevant segment using keyword, exact-query, and chapter-aware signals; Top-1 hit is `1/1`
- safety behavior: a chapter-11 future segment is filtered before ranking reaches the prompt
- explanation output: includes `exact_query_match` and `chapter_distance=2`
- evidence: `src/test/java/com/novel/agent/service/MilvusSearchServiceTest.java`

Conclusion: the domain ranking layer can correct a semantically plausible but writing-useless raw-score result. The case is a deterministic CI fixture; live latency must be measured with the benchmark runner.

## Recorded Case 2: Post-Generation Regression Gate

Scenario: validate the generated continuation before returning it to the caller.

- before optimization: the short fixture output is returned with `status=warn` and `warningCount=1` (`content_too_short`)
- after optimization: a structured continuation that includes scene action, character state, and the next hook returns `status=pass` and `warningCount=0`
- rule boundary: this is a deterministic regression gate, not a claim that a rule-based check can replace human editorial review
- evidence: `src/test/java/com/novel/agent/controller/NovelControllerTest.java`, method `generateChapterIncludesTraceTokenUsageAndAdvancedConsistencyWarnings`

Conclusion: the endpoint exposes a visible quality decision instead of silently returning every model response. The warning payload can be used to trigger review, retry, or a later model-specific evaluator.

## Recommended Evidence Capture

For each case, store:

- input query or writing instruction
- top retrieved memory blocks
- explanation fields for ranking
- final generated output excerpt
- token cost snapshot
- one-line conclusion about improvement or regression

## Interview Usage

Use these cases when asked how quality is proven beyond retrieval metrics and how long-form consistency is controlled.
