# Contributing to CodePlanGUI

Thank you for your interest in contributing! Please read this guide before opening a PR or issue.

---

## Getting Started

1. Fork the repository and clone your fork
2. Create a branch from `master` with a descriptive name:
   ```
   feat/your-feature-name
   fix/the-bug-description
   ```
3. Make your changes, then open a Pull Request

### Build Requirements

- IntelliJ IDEA 2023.1+
- JDK 17 (Corretto 17 recommended — other versions may break the Kotlin compiler)
- Node.js 18+ (for the webview frontend under `webview/`)

### Build & Run

```bash
# Run plugin in a sandboxed IDE instance
./gradlew runIde

# Build the webview frontend
cd webview && npm install && npm run build

# Run all tests
./gradlew test
```

---

## Commit Message Convention

This project follows [Conventional Commits](https://www.conventionalcommits.org/).

```
<type>(<scope>): <short description>
```

| Type | When to use |
|------|-------------|
| `feat` | New feature |
| `fix` | Bug fix |
| `docs` | Documentation only |
| `refactor` | Code change that is not a fix or feature |
| `test` | Adding or fixing tests |
| `chore` | Build, CI, dependency updates |
| `perf` | Performance improvement |
| `ci` | CI/CD pipeline changes |

**Scopes** (optional but encouraged): `execution`, `ui`, `webview`, `settings`, `chat`, `commit`

**Examples:**
```
feat(execution): add cross-platform shell abstraction
fix(ui): correct card collapse animation on resize
docs: update setup instructions in README
test(execution): add ShellPlatform unit tests for Windows
```

**Common mistake — wrong type:**

`fix` means a bug fix only. If you add a new method, new UI, or new behavior, it's `feat` even if it's small. Using `fix` for a feature silently breaks automated changelog generation.

```
# Wrong — this adds new streaming behavior, not a bug fix
fix: add async stream execution and log bridge

# Correct
feat(execution): add async stream execution with log bridge
```

**Breaking changes** — append `!` after the type and add a footer:
```
feat(settings)!: rename provider config fields

BREAKING CHANGE: `apiUrl` is now `endpointUrl` in saved settings
```

---

## Pull Request Guidelines

Before opening a PR, run the pre-check script:

```bash
bash scripts/check-pr.sh
```

This runs Kotlin tests, webview build, and checks for debug logs. Fix any failures before submitting.

- **One PR, one concern** — keep changes focused; unrelated fixes belong in separate PRs
- **PR title must follow Conventional Commits** — the title becomes the squash commit message
- **Fill in the PR template** — include a 变更文件 table and a 测试计划 checklist
- **Link related issues** — use `Closes #123` in the PR description

---

## Code Style

- Kotlin: follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- TypeScript (webview): `strict: true`, no `any` without a comment explaining why
- No leftover debug logs or commented-out code in merged PRs

---

## Reporting Issues

Please use the GitHub issue tracker. Include:
- IDEA version and OS
- Steps to reproduce
- Expected vs actual behavior
- Relevant log output (Help → Show Log in Explorer/Finder)
