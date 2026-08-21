# Environment Runbook

Updated: 2026-08-15

## Purpose

This document defines the supported single-node boundary for `novel_agent` and gives a safe preflight command for Windows development. The command checks connectivity only; it never reads or prints database passwords or model keys.

The project is for personal writing use. It does not use Redis or RabbitMQ, does not require a distributed lock, and does not depend on a message queue for imports.

## Current Topology

```text
Windows 11 application
  ├─ local MySQL :3306                 required by JPA and evaluation history
  ├─ cloud Milvus :19530               required by vector search and import
  ├─ cloud embedding provider :443     required by vector search and import
  └─ local JMeter                       used only for concurrency tests
```

Long-running import and Milvus finalize tasks run through a single-worker, zero-queue local executor. If another maintenance task is active, the API returns a retryable failure instead of silently building an in-memory queue.

## Prerequisites

- JDK 17 and Maven 3.9+
- Windows PowerShell 5.1+ or PowerShell 7+
- local MySQL `8.x` with the `novel_agent` schema initialized
- reachable Milvus endpoint and configured embedding provider
- `.env` copied from `.env.example`; credentials remain local and are never committed

## Connectivity Preflight

Run this before starting the application. The required checks are MySQL, Milvus, and the embedding provider.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-infrastructure.ps1 `
  -MilvusHost $env:MILVUS_HOST `
  -OutputPath artifacts/infrastructure-preflight.json
```

If the cloud service uses a different host, pass the actual host explicitly. Do not place credentials in the command line or in a committed report.

Typical output:

```text
[PASS] mysql [localhost:3306]: TCP connection established
[PASS] milvus [<cloud-host>:19530]: TCP connection established
[PASS] embedding-provider [https://api.siliconflow.cn/v1]: HTTP endpoint reachable (status 401)
[SKIP] application-health: skipped; add -CheckApplication after the app starts
Overall: PASS (required failures: 0, optional failures: 0)
```

A `401`, `404`, or `405` from the embedding base URL proves network reachability only; authentication and model-level behavior are validated by the application-backed RAG/import commands.

## Latest Verified Snapshot

The required dependency-only preflight passed on `2026-08-11` for local MySQL, cloud Milvus, and the SiliconFlow embedding endpoint. The application process was not running during that probe, so `application-health` remained explicitly `SKIP` rather than an unverified claim.

- required: MySQL `3306`, Milvus `19530`, and embedding-provider HTTPS reachability passed
- embedding response: HTTP `404` from the provider base path proves network reachability only; it is not a model authorization result
- sanitized evidence: `docs/benchmarks/infrastructure-preflight-live-20260811.json`

## Application Health

After starting Spring Boot in another terminal, check the application contract as well:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-infrastructure.ps1 `
  -MilvusHost $env:MILVUS_HOST `
  -CheckApplication `
  -OutputPath artifacts/infrastructure-preflight-running.json
```

Then run the repository smoke test:

```powershell
./scripts/smoke-test.ps1
```

For a provider-backed retrieval check, use the profile-specific runner documented in `docs/BENCHMARK_REPORT.md`.

## Failure Interpretation

- `mysql FAIL`: verify the local MySQL service, port `3306`, schema, and `.env` credentials; a TCP pass alone does not prove authentication.
- `milvus FAIL`: verify the cloud security-group rule, public port `19530`, and the host in `.env` or the command line.
- `embedding-provider FAIL`: verify DNS/HTTPS access and the provider base URL; the preflight intentionally does not send an API key.
- `application-health FAIL`: inspect Spring Boot logs after required infrastructure passes.
- import/finalize returns a local-task conflict: wait for the current task to finish, then retry; do not submit repeated requests in a loop.

## Security Rules

- Never commit `.env`, access keys, passwords, or raw benchmark output containing secrets.
- Prefer cloud security-group allowlists and TLS termination over exposing Milvus broadly.
- Keep `NOVEL_AGENT_ADMIN_API_KEY` configured for management and finalize endpoints.
- Use temporary positive `novelId` values and `-Cleanup` for live import benchmarks.
