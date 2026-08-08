# Novel Agent

Novel Agent is a vertical AI application for long-form web novel writing. It focuses on one complete product chain: structured story knowledge, retrieval-aware memory, controlled generation, cost governance, and RAG evaluation.

[![CI](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

## Scope

This repository is intentionally scoped to the novel-writing product itself. Generic agent-platform capabilities should stay in the separate `newagent` repository.

## Features

- Structured domain modeling for novels, chapters, characters, factions, skills, artifacts, events, and inspiration.
- Retrieval-augmented writing memory with Milvus vector search and chapter-aware filtering.
- Chapter generation orchestration built around DeepSeek prompts and retrieved context.
- Token cost governance with budget limits, usage history, and a lightweight dashboard.
- RAG evaluation workflows for recall, precision, MRR, keyword coverage, and latency.

## Tech Stack

- Java 17
- Spring Boot 3.5
- Spring Web, WebFlux, Validation, JPA
- MySQL
- Milvus
- LangChain4j
- DeepSeek API
- spring-dotenv

## Repository Layout

```text
.github/workflows/ci.yml   GitHub Actions CI
.env.example               Local environment template
pom.xml                    Maven project definition
sql/init.sql               Database bootstrap script
src/main                   Application source and resources
src/test                   Tests
docs/PROJECT_DETAILS.md    Product details and API examples
docs/DATAFLOW.md           Data flow notes
docs/ROADMAP.md            Project roadmap
docs/ARCHITECTURE.md       Repository structure notes
```

## Quick Start

```bash
git clone https://github.com/888newstep/novel_agent.git
cd novel_agent
```

```powershell
Copy-Item .env.example .env
mvn test -DskipITs
mvn spring-boot:run
```

## Documents

- `docs/PROJECT_DETAILS.md`
- `docs/DATAFLOW.md`
- `docs/ROADMAP.md`
- `docs/ARCHITECTURE.md`
- `CONTRIBUTING.md`
- `SECURITY.md`

## Engineering Baseline

- Flat repository layout with a single build entry at the repo root.
- Open-source metadata and CI ready for GitHub usage.
- Redundant IDE files, patch files, build outputs, and compile logs removed.
- Unit and controller tests available and passing.

## License

Apache License 2.0
