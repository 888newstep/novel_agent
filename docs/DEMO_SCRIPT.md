# `novel_agent` Demo Script

Updated: 2026-08-09

## Goal

Use this script for GitHub walkthrough and interview presentation.
The objective is to explain the project in five minutes and show one complete vertical AI workflow.

## Opening

Start with this division of labor:

- `newagent` solves generic AI platform problems.
- `novel_agent` solves one vertical product problem: long-form novel writing.

Then explain the chain:

- structured story knowledge
- chapter-aware retrieval
- writing memory assembly
- controlled generation
- token cost governance
- offline evaluation

## Demo Order

1. show structured domain objects
2. show retrieval and memory assembly
3. show generation entry
4. show cost control and evaluation endpoints
5. explain why this repo complements `newagent`

## Scenario A

Goal: show chapter-aware retrieval before writing.

Suggested API flow:

```text
POST /api/v1/novel
POST /api/v1/novel/{novelId}/chapter
POST /api/v1/novel/{novelId}/event
POST /api/v1/novel/{novelId}/character
POST /api/v1/novel/{novelId}/search
GET  /api/v1/novel/{novelId}/memory
```

Talking points:

- retrieval is filtered by `novelId` and chapter context
- results are assembled into writing memory blocks
- unresolved events and key characters should surface before continuation writing

## Scenario B

Goal: show controlled continuation generation.

Suggested API flow:

```text
POST /api/v1/novel/{novelId}/generate
GET  /api/admin/cost/summary
GET  /api/admin/cost/records
```

Talking points:

- generation uses retrieved story context instead of raw prompting
- token usage and budget limits are visible
- long-text generation is treated as a governed workflow

## Scenario C

Goal: show offline evaluation and iteration.

Suggested API flow:

```text
POST /api/v1/novel/evaluate/segments
GET  /api/v1/novel/evaluate/report
GET  /api/v1/novel/evaluate/test-cases
```

Talking points:

- evaluation tracks recall, precision, MRR, keyword coverage, and latency
- retrieval tuning is discussed with evidence instead of intuition

## Follow-Up Questions

- why novel writing needs different retrieval signals from general QA
- how character behavior stays consistent across chapters
- how retrieval quality is evaluated for writing memory
- what happens when token budget is exceeded
- why `newagent` and `novel_agent` are split into two repositories
