# Contributing

Thanks for your interest in improving `Novel Agent`.

## Before You Start

- Search existing issues and pull requests first.
- Keep changes focused and easy to review.
- Prefer root-cause fixes over temporary patches.

## Local Development

```bash
git clone https://github.com/888newstep/novel_agent.git
cd novel_agent/novel_agent
```

Create local configuration:

```powershell
Copy-Item .env.example .env
```

Run verification:

```bash
mvn test -DskipITs
```

## Pull Requests

1. Fork the repository.
2. Create a branch such as `feature/your-change` or `fix/your-fix`.
3. Make the smallest complete change.
4. Run the relevant tests.
5. Open a pull request with a clear summary, rationale, and validation notes.

## Commit Messages

Use Conventional Commit style when possible:

- `feat:` new behavior
- `fix:` bug fix
- `docs:` documentation only
- `refactor:` internal code cleanup
- `test:` test-only change
- `chore:` repository or tooling maintenance

## Scope Guidance

- `novel_agent/` focuses on the vertical novel-writing product.
- Generic agent-platform capabilities should stay out of this repository.
