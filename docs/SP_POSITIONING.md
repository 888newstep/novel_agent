# `novel_agent` Positioning For Autumn Recruitment

Updated: 2026-08-09

## 1. Why this repo matters

`newagent` already proves that you can build a general AI platform:

- Adaptive RAG
- ReAct agent loop
- multi-agent collaboration
- platform observability
- general evaluation framework

If `novel_agent` repeats those points, the two projects dilute each other.
For SP-level resume presentation, the better strategy is division of labor.

## 2. Division of labor with `newagent`

`newagent` should answer:

- can you build a reusable AI platform?
- can you abstract common agent capabilities?
- can you handle generic RAG, tools, and orchestration complexity?

`novel_agent` should answer:

- can you go deep in one business scenario?
- can you convert retrieval and generation into a product workflow?
- can you quantify quality, cost, and consistency in a vertical AI application?

## 3. What recruiters should see from `novel_agent`

Recruiters should quickly recognize four points:

1. This is not a demo chat app. It is a writing product with domain objects and business rules.
2. Retrieval is scenario-aware, not generic top-K vector search.
3. Generation is controlled by memory assembly, consistency checks, and cost limits.
4. The repo has measurable evaluation, test baseline, and open-source readability.

## 4. Best optimization direction

For this repo, the highest-value upgrades are:

- deepen writing-memory construction instead of adding generic agent abstractions
- strengthen retrieval ranking and explanation instead of adding more model providers
- add generation consistency checks instead of adding more platform routing layers
- produce benchmark evidence instead of only describing architecture
- improve demo quality instead of expanding tech stack width

## 5. Suggested new documents

To support both GitHub and interviews, this repo should contain:

- `docs/ROADMAP.md`: execution roadmap
- `docs/SP_POSITIONING.md`: project boundary and resume strategy
- `docs/DEMO_SCRIPT.md`: how to present the project in 5 minutes
- `docs/BENCHMARK_REPORT.md`: retrieval, latency, and token-cost results
- `docs/WRITING_QUALITY_CASES.md`: before/after cases for consistency and writing memory

## 6. Suggested interview narrative

The project should be narrated in this order:

1. Why novel writing is harder than generic QA
2. How structured knowledge and chapter-aware retrieval are modeled
3. How writing memory is assembled before generation
4. How consistency, cost, and stability are controlled
5. How quality is measured offline and iterated with evidence

## 7. Target resume contribution

After this repo is strengthened, it should contribute to your resume as the "vertical AI product" project, while `newagent` remains the "general AI platform" project.

That pairing is stronger than trying to make one repo do everything.
