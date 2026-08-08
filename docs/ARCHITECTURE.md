# Architecture

## Goal

This repository is a vertical AI application for long-form web novel writing. It stays at the product layer instead of becoming a generic agent platform.

## Directory Responsibilities

- `src/main/java/com/novel/agent/config`: model, Milvus, and retrieval configuration.
- `src/main/java/com/novel/agent/controller`: HTTP APIs and controller-level exception handling.
- `src/main/java/com/novel/agent/entity`: domain entities for novel writing.
- `src/main/java/com/novel/agent/repository`: JPA persistence layer.
- `src/main/java/com/novel/agent/service`: retrieval, generation, import, evaluation, and cost-governance services.
- `src/main/java/com/novel/agent/utils`: small utility helpers.
- `src/main/resources`: runtime configuration, dataset files, and static assets.
- `src/test`: service and controller tests.
- `sql`: database bootstrap scripts.
- `docs`: product and engineering documentation.

## Engineering Principles

- One repository, one clear product goal.
- Build, test, and read from the repository root.
- Separate business code, runtime assets, and project documentation.
- Do not commit IDE settings, build artifacts, patch files, or local logs.

## Build Entry

```bash
mvn test -DskipITs
mvn spring-boot:run
```
