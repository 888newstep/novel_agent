# Environment Runbook

Updated: 2026-08-11

## Purpose

This document defines the supported local/cloud boundary for `novel_agent` and gives a safe preflight command for Windows development. The command checks connectivity only; it never reads or prints database passwords, model keys, or RabbitMQ credentials.

## Current Topology

```text
Windows 11 application
  ├─ local MySQL :3306                 required by JPA and evaluation history
  ├─ local Redis :6379                 available, optional, not used by the current import/retrieval path
  ├─ cloud Milvus :19530               required by vector search and import
  ├─ cloud embedding provider :443     required by vector search and import
  └─ cloud RabbitMQ :5672/:15672       available, optional, not used by the current import/retrieval path
```

The dependency boundary is deliberate. Redis and RabbitMQ should not be added to the critical path merely because the services are available. If a future feature needs asynchronous import jobs or distributed cache, it must first define ownership, failure semantics, idempotency, metrics, and a benchmark.

## Prerequisites

- JDK 17 and Maven 3.9+
- Windows PowerShell 5.1+ or PowerShell 7+
- local MySQL `8.x` with the `novel_agent` schema initialized
- reachable Milvus endpoint and configured embedding provider
- optional local Redis and cloud RabbitMQ when validating the surrounding development environment
- `.env` copied from `.env.example`; credentials remain local and are never committed

## Connectivity Preflight

Run this before starting the application. The required checks are MySQL, Milvus, and the embedding provider. Redis and RabbitMQ are reported as optional checks.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-infrastructure.ps1 `
  -MilvusHost $env:MILVUS_HOST `
  -RabbitMqHost $env:RABBITMQ_HOST `
  -OutputPath artifacts/infrastructure-preflight.json
```

If the cloud services use a different host, pass the actual host explicitly. Do not place credentials in the command line or in a committed report.

Typical output:

```text
[PASS] mysql [localhost:3306]: TCP connection established
[PASS] milvus [<cloud-host>:19530]: TCP connection established
[PASS] embedding-provider [https://api.siliconflow.cn/v1]: HTTP endpoint reachable (status 401)
[PASS] redis [localhost:6379]: TCP connection established
[PASS] rabbitmq-amqp [<cloud-host>:5672]: TCP connection established
[PASS] rabbitmq-management [<cloud-host>:15672]: TCP connection established
[SKIP] application-health: skipped; add -CheckApplication after the app starts
Overall: PASS (required failures: 0, optional failures: 0)
```

A `401`, `404`, or `405` from the embedding base URL still proves network reachability; authentication and model-level behavior are validated by the application-backed RAG/import commands.

## Latest Verified Snapshot

The dependency-only preflight passed on `2026-08-11` with local MySQL/Redis, cloud Milvus/RabbitMQ, and the SiliconFlow embedding endpoint. The application process was not running during this probe, so `application-health` remains explicitly `SKIP` rather than an unverified claim.

- required: MySQL `3306`, Milvus `19530`, and embedding-provider HTTPS reachability passed
- optional: Redis `6379`, RabbitMQ AMQP `5672`, and RabbitMQ management `15672` passed
- embedding response: HTTP `404` from the provider base path proves network reachability only; it is not a model authorization result
- sanitized evidence: `docs/benchmarks/infrastructure-preflight-live-20260811.json`

## Application Health

After starting Spring Boot in another terminal, check the application contract as well:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/check-infrastructure.ps1 `
  -MilvusHost $env:MILVUS_HOST `
  -RabbitMqHost $env:RABBITMQ_HOST `
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
- `redis FAIL`: warning only in the current architecture; do not treat it as an application outage.
- `rabbitmq-* FAIL`: warning only in the current architecture; verify both AMQP and management ports only if the service is part of a future feature.
- `application-health FAIL`: inspect Spring Boot logs after required infrastructure passes.

## Security Rules

- Never commit `.env`, access keys, passwords, or raw benchmark output containing secrets.
- Prefer cloud security-group allowlists and TLS termination over exposing management ports broadly.
- Treat `15672` as an administration port; restrict it to trusted IPs even when `5672` is open to the application.
- Use temporary positive `novelId` values and `-Cleanup` for live import benchmarks.