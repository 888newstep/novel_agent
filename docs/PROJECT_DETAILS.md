# Project Details

## Positioning

Novel Agent is built for web novel writing scenarios. It focuses on structured world knowledge, writing memory retrieval, continuation generation, cost governance, and measurable RAG quality.

## Core Capabilities

- Novel entity management for novels, chapters, characters, factions, artifacts, skills, events, and inspiration.
- Writing retrieval based on Milvus vector search with chapter awareness and event filtering.
- Generation flow driven by DeepSeek prompts and retrieved context assembly.
- Cost governance with token accounting, limits, usage details, and a dashboard page.
- RAG evaluation with offline datasets, report comparison, and historical summaries.

## Project Structure

```text
src/main/java/com/novel/agent
|- config
|- controller
|- entity
|- exception
|- repository
|- service
`- utils

src/main/resources
|- application.yml
|- rag_eval_dataset.json
`- static/cost-dashboard.html

sql/init.sql
docs/DATAFLOW.md
docs/ROADMAP.md
```

## Quick Start

### Prerequisites

- JDK 17
- Maven 3.9+
- MySQL 8.x
- Milvus 2.x
- A valid `DEEPSEEK_API_KEY`
- Embedding model configuration if retrieval is enabled

### Run

```powershell
Copy-Item .env.example .env
mvn test -DskipITs
mvn spring-boot:run
```

## API Areas

- Novel domain management
- Retrieval and generation
- External knowledge import
- Cost control and reporting
- RAG evaluation
