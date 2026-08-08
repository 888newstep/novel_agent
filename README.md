# Novel Agent

A vertical AI application for long-form web novel writing, built around structured story knowledge, retrieval-aware writing memory, controlled generation, cost governance, and RAG evaluation.

[![CI](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/novel_agent/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot 3](https://img.shields.io/badge/Spring%20Boot-3.5-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

## Repository Layout

This repository currently keeps the application source under the `novel_agent/` subdirectory.

- `novel_agent/` - main Spring Boot application
- `.github/workflows/ci.yml` - repository CI workflow
- `LICENSE` - open source license
- `CONTRIBUTING.md` - contribution guide
- `CODE_OF_CONDUCT.md` - community behavior expectations
- `SECURITY.md` - vulnerability reporting policy

## What The Project Demonstrates

- structured novel domain modeling with MySQL + JPA
- chapter-aware and writing-oriented retrieval with Milvus
- generation orchestration with DeepSeek and prompt assembly
- token cost governance and admin endpoints
- RAG evaluation with category summaries, report comparison, and history

## Quick Start

```bash
git clone https://github.com/888newstep/novel_agent.git
cd novel_agent/novel_agent
```

PowerShell:

```powershell
Copy-Item .env.example .env
mvn test -DskipITs
mvn spring-boot:run
```

## Key Documents

- `novel_agent/README.md` - application overview and API examples
- `novel_agent/datachain.md` - data flow notes
- `novel_agent/NOVEL_AGENT_PLAN.md` - current implementation roadmap

## Open Source Baseline

- root-level repository metadata for GitHub presentation
- checked-in `.env.example`
- CI workflow for compile + test
- service and controller test baseline
- explicit exception handling and evaluation reporting

## License

Apache License 2.0
