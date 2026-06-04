# Engineering Principles & Teamwork Protocol

This document is the authoritative reference for how we work on GridMaster. All contributors (human and AI) must follow these principles. It is a living document — propose changes via PR.

---

## 1. Design Before Code

**No implementation PR is merged without a prior approved design.**

The sequence is strict:

```
UX design doc (if UI/interaction change)
        ↓ PR → review → merge
Engineering design doc  (docs/engineering/<module>.md)
        ↓ PR → review → merge
Implementation
        ↓ PR → CI green → review → merge
Integration / E2E tests (if not in implementation PR)
        ↓ PR → CI green → merge
```

- A design doc PR may be small — a few hundred words is enough if the design is clear.
- Implementation PRs that arrive without a linked design doc are blocked, not reviewed.
- If a design changes materially during implementation, update the design doc in the same PR.

---

## 2. Engineering Design Doc Standard

Every non-trivial submodule gets a design doc at `docs/engineering/<nn>-<module-name>.md`.

Minimum required sections:

```markdown
# <Module Name>

## Purpose
One paragraph: what problem this module solves and why it exists here.

## Scope
What is in scope. What is explicitly out of scope.

## Key Concepts / Domain Model
Entities, their fields, and relationships. Use tables or diagrams.

## API / Interface
Public interfaces, REST endpoints, WebSocket message schemas, or function signatures.
Use concrete types (Kotlin data classes, TypeScript interfaces, JSON examples).

## Design Decisions & Rationale
Numbered list of non-obvious choices and why they were made.
Include alternatives considered and why they were rejected.

## Error Handling
How failures surface. What the caller should do.

## Testing Strategy
Unit tests: what is mocked, what is tested in isolation.
Integration tests: what real dependencies are exercised.
Edge cases to cover.

## Open Questions
Unresolved issues that will be decided during or after implementation.
```

---

## 3. Git Workflow

### Branch naming
```
stage/<n>/<short-description>
```
Examples: `stage/0/repo-scaffold`, `stage/1/power-flow-adapter`, `stage/3/babylon-scene-foundation`

### Commit messages
Follow Conventional Commits:
```
<type>(<scope>): <short summary>

[optional body — wrap at 72 chars]
[optional footer: Closes #<issue>]
```
Types: `feat`, `fix`, `test`, `docs`, `refactor`, `chore`, `ci`

Examples:
```
feat(engine): add AC power flow adapter wrapping PowSyBl LoadFlow
docs(engineering): add design doc for network model
test(engine): add IEEE 14-bus integration test for contingency runner
ci: add ktlint and ESLint checks to PR workflow
```

### Pull requests
- One logical change per PR. Don't bundle unrelated fixes.
- PR title follows the same Conventional Commits format.
- Fill in the PR template (see `.github/PULL_REQUEST_TEMPLATE.md`).
- PRs against `main` require: CI green + at least one review approval.
- Keep PRs small enough to review in one sitting (aim for <400 lines changed; split larger work into sequential PRs).

### Branch protection on `main`

> **Note**: GitHub branch protection rules require GitHub Pro for private repos. This repo operates on a free private plan, so rules are not GitHub-enforced. The following constraints are **mandatory by convention** and must be followed without exception:

- **Never push directly to `main`**. All changes go through a PR on a named branch.
- **Never merge your own PR**. A second person (or Claude, for human-authored PRs) must review and approve.
- **Never merge with a red CI**. If CI fails, fix it before merging — no exceptions, no "I'll fix it in the next PR".
- **Never merge with unresolved review comments**. Resolve or explicitly acknowledge each comment before merging.
- **Critical PRs** (physics engine, game engine core, WebSocket protocol, session model, CI changes) require a review from `siliconimaginations` before merge. Claude will request this review when opening such PRs.

Violating these rules undermines the entire review process. Treat them as hard constraints.

---

## 4. Code Standards

### Kotlin (backend)

- **Style**: [ktlint](https://github.com/pinterest/ktlint) enforced in CI. No suppressions without a comment explaining why.
- **Naming**: follow Kotlin conventions — `camelCase` for functions/properties, `PascalCase` for classes, `UPPER_SNAKE_CASE` for constants.
- **Null safety**: never use `!!` without a comment justifying it. Prefer `?: error(...)` or `requireNotNull(...)`.
- **Immutability first**: prefer `val` over `var`; prefer immutable data classes for DTOs and game state.
- **Side effects**: keep domain logic (game engine, physics wrappers) free of I/O. I/O belongs in the API and persistence layers.
- **Coroutines**: use structured concurrency; never launch a coroutine that escapes its scope without explicit lifecycle management.
- **Error handling**: use `Result<T>` or sealed error types for expected failures; throw exceptions only for programming errors. Never swallow exceptions silently.

### TypeScript (frontend)

- **Style**: ESLint + Prettier enforced in CI.
- **Strict mode**: `"strict": true` in `tsconfig.json`. No `any` types without a `// eslint-disable` comment and reason.
- **State**: all game state lives in the Zustand store; components are pure renderers. No local state for data that comes from the server.
- **Babylon.js scene**: scene mutation lives in `src/scene/`; React components never touch the Babylon engine directly.
- **Async**: use `async/await`; avoid `.then()` chains. Handle errors at the boundary (the API client layer), not scattered through components.

### General

- **No magic numbers**: name every constant that has domain meaning (e.g., `MAX_LINE_LOADING_PERCENT = 100.0`, `TICK_INTERVAL_MS = 1000`).
- **Delete dead code**: don't comment out code — commit history preserves it.
- **Dependencies**: add a dependency only when it clearly earns its place. Document why in the PR description.

---

## 5. Testing Standards

| Layer | Tool | Coverage target |
|-------|------|----------------|
| Physics engine (PowSyBl wrappers) | JUnit 5 + AssertJ | ≥ 90% |
| Game engine (clock, events, commands) | JUnit 5 + Mockk | ≥ 80% |
| REST / WebSocket API | Spring Boot Test (integration) | key paths |
| Frontend components | Vitest + React Testing Library | ≥ 70% |
| E2E (critical flows) | Playwright | tutorial M1–3, challenge launch |

Rules:
- Tests live next to the code they test (`src/test/` mirrors `src/main/`).
- Integration tests that hit the real PowSyBl solver are tagged `@Tag("integration")` and may be skipped in fast CI but must run in full CI.
- A PR that reduces coverage without a documented reason is rejected.
- Test names describe behaviour, not implementation: `"power flow on an overloaded line returns a violation result"`, not `"testPowerFlow2"`.

---

## 6. Architecture Principles

### Server-authoritative
The backend is the source of truth for all game state. The frontend renders and sends commands; it never computes physics or advances the clock independently.

### Physics isolation
PowSyBl is wrapped behind an internal API (`engine` package). No other module imports PowSyBl classes directly. This allows the solver to be swapped, mocked in tests, or called asynchronously without touching game logic.

### Tick budget
Each game tick must complete within its wall-clock slot. Power flow on ≤1000 buses takes a few seconds at most; the game clock must account for this. If a tick runs long, the clock slips — it does not drop ticks or corrupt state.

### Stateless API layer
REST and WebSocket controllers are stateless. All state is in the game session (persisted in the DB). This makes the API layer easy to test and future-proof for horizontal scaling.

### Event-driven environment
External events (weather, economic, policy) are generated by an event engine and applied to the network model as commands — the same pathway as player actions. This means events are testable, replayable, and auditable via the event log.

### Separation of concerns in the frontend
```
src/scene/     — Babylon.js scene, meshes, animation. No business logic.
src/ui/        — React panels and overlays. No Babylon.js imports.
src/state/     — Zustand store. Single source of truth on the client.
src/api/       — WebSocket client and REST calls. No UI or scene code.
```

---

## 7. CI / CD Requirements

Every PR must pass:
1. **Build**: `./gradlew build` (backend) and `npm run build` (frontend)
2. **Lint**: ktlint (backend), ESLint + Prettier (frontend)
3. **Unit tests**: all unit tests green
4. **Integration tests**: tagged integration tests green (may run in a separate workflow)

CI failures block merge — no exceptions.

Future gates (Stage 7+):
- Playwright E2E on PR
- Coverage threshold enforcement
- Docker image build and smoke test

---

## 8. Documentation Standards

- Every public Kotlin function or class with non-obvious behaviour gets a KDoc comment.
- Every public TypeScript function or type gets a JSDoc comment.
- REST endpoints are documented with examples in the engineering design doc — not in code comments.
- The `docs/` folder is the canonical home for design and architecture docs. Don't scatter architecture decisions in Slack/chat; write them up and commit them.

---

## 9. AI Collaboration Protocol

When Claude (AI assistant) works on this codebase:

- Claude follows the same design-before-code sequence as human contributors.
- Claude does not push directly to `main`. All changes go through a PR.
- Claude writes a design doc PR first for any new submodule, awaiting review before implementation.
- Claude flags uncertainty explicitly — if a design choice is non-obvious, it is listed under "Design Decisions & Rationale" with the tradeoff explained.
- Claude does not silently change the scope of a task. If implementation reveals the design needs to change, Claude raises it in the PR description or chat before making the change.
- Claude treats this document as a hard constraint, not a suggestion.

### GitHub identity

Claude operates as **[`nagasawa94`](https://github.com/nagasawa94)** on GitHub — a dedicated bot account with Write access to this repo. All branches pushed and PRs opened by Claude will show `nagasawa94` as the author, keeping Claude's contributions clearly distinct from Rick's (`siliconimaginations`).

### Review workflow

| PR type | Author | Reviewer | How |
|---------|--------|----------|-----|
| Claude opens a PR | `nagasawa94` | `siliconimaginations` (Rick) | Rick reviews diff on GitHub and merges when satisfied |
| Rick opens a PR | `siliconimaginations` | `nagasawa94` (Claude) | Claude reviews in chat and posts inline comments via GitHub API |
| Critical PRs (engine, protocol, CI) | either | both | Claude flags in PR description; Rick must explicitly approve before merge |

**Merging without reviewing the diff is a process violation regardless of CI status.**

---

## 10. Definition of Done

A feature is **done** when:
- [ ] Design doc merged to `main`
- [ ] Implementation PR approved and merged
- [ ] CI passes (build + lint + tests)
- [ ] Coverage targets met
- [ ] No unresolved review comments
- [ ] `WORK_PLAN.md` stage updated if the feature completed a stage milestone
