# RAG Evaluation History

## Purpose

The evaluation endpoint still returns the full report for debugging, but long-term history is stored as a bounded aggregate view.
This keeps trend analysis useful without persisting query details or novel source text.

## Data Flow

1. `POST /api/v1/novel/evaluate/segments` executes the selected profile against Milvus.
2. The service builds the full in-memory report, including query details and category breakdowns.
3. A best-effort write stores only scalar metrics in `rag_evaluation_snapshots`.
4. Startup loads recent rows into the profile/novel history cache.
5. `GET /api/v1/novel/evaluate/history` reads the newest aggregate snapshots first.

Database write or read failures are logged and do not block the evaluation response; the service falls back to the in-memory history for the current process.

## Persisted Fields

- `profile_name`, `dataset_version`, and `novel_id`
- `top_k`, query count, and queries with a relevant result
- `Recall@K`, `Precision@K`, `MRR`, keyword coverage
- average, minimum, maximum, `P95`, and `P99` latency
- average retrieved context characters and estimated tokens
- evaluation timestamp and database creation timestamp

The table intentionally has no query, content, score explanation, or novel-text columns.
`novel_id` is not a foreign key because the shared evaluation fixture uses `novelId=0` without requiring a `novels` row.

## API

```text
POST /api/v1/novel/evaluate/segments?novelId=0&topK=5&profile=writing-zh-live-v1
GET  /api/v1/novel/evaluate/history?novelId=0&profile=writing-zh-live-v1&limit=5
```

History is isolated by `profile + novelId`, capped at 50 rows per request, and returned newest first.

## Migration

For a fresh database, `sql/init.sql` includes the table.
For an existing database, apply the incremental migration before starting with `ddl-auto=validate`:

```powershell
Get-Content -Raw -Encoding UTF8 sql/migrations/V20260810__add_rag_evaluation_snapshots.sql |
  mysql --protocol=TCP -h 127.0.0.1 -P 3306 -u $env:MYSQL_USERNAME novel_agent
```

Use the normal MySQL credential mechanism for the local environment; do not put passwords in shell history or committed files.

## Evidence

- `src/test/java/com/novel/agent/service/RagEvaluationServiceTest.java`
- `src/test/java/com/novel/agent/controller/RagEvaluationControllerTest.java`
- `docs/benchmarks/rag-evaluation-history-live-20260810.json`
