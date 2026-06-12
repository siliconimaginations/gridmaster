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

## 2a. UX Design Doc Standard

UX documents live at `docs/ux/<nn>-<feature-name>.md` and are required before any frontend feature that introduces new UI surface.

Minimum required sections:

```markdown
# UX: <Feature Name>

## Purpose
What problem this UI solves for the player. One paragraph.

## [Component anatomy / Layout]
ASCII wireframe or table describing the visual structure.
Use the approved panel pattern for event/action panels:
coloured header · causal flow strip · 3 metric cards · loading bar · option cards · apply button.

## [Per-element or per-state content]
Tables: what data is shown, example values, action buttons.

## Open questions
Unresolved layout or interaction questions.
```

UX docs should be **scannable, not prose** — labels, tables, and ASCII layouts only.
Details and rationale go in `ⓘ` tooltip annotations, not inline paragraphs.

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

- Every public Kotlin symbol (class, interface, enum, function, or property) gets a KDoc comment. This includes simple DTOs, enums, and extension functions — not just non-obvious behaviour.
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

### PR classification

Every PR is either **non-critical** or **critical**:

| Type | Examples |
|------|---------|
| **Non-critical** | CI changes, tooling, coverage, linting, test fixes, doc updates, refactors within an approved design |
| **Critical** | Architecture decisions, API/WebSocket design, physics engine changes, game mechanics, UX direction, new submodule design |

### Review workflow

#### Non-critical PRs
1. `nagasawa94` opens the PR. Rick is **not** assigned as reviewer.
2. Claude polls every ~30 s for: all CI checks green · Gemini AI review present · no unresolved 🔴/🟠 Gemini issues.
3. Minor Gemini suggestions (🟡) → add a `// TODO:` in code and open a GitHub issue to track. Do not block merge.
4. Once all criteria are met, Claude merges autonomously and notifies Rick in chat.
5. After merging, Claude **checks the GitHub Projects board** to determine the next task (see §9 "Determining the next task").

#### Critical PRs
1. `nagasawa94` opens the PR with `**PR Classification:** CRITICAL` in the description and assigns `siliconimaginations` as reviewer.
2. Poll CI and Gemini; address ALL Gemini issues (critical/major/minor) — same as non-critical PRs. Do not wait for Rick before fixing Gemini findings.
3. Do **not** merge without Rick's explicit approval.
4. **Do not sit idle while waiting.** Continue working on other WORK_PLAN tasks that are not blocked by the open critical PR. If the next task depends on unmerged code, branch off the unreviewed branch and continue; rebase/merge onto main once the blocking PR lands.

#### Critical → Non-critical transition

Rick signals a PR can become non-critical by adding `_NCP` to the PR description or a review comment.
Meaning: major design decisions are settled; remaining review is routine.

Claude's response:
- **Agrees:** update the PR description to `**PR Classification:** NON_CRITICAL (_NCP)`, remove the assigned reviewer, and switch to autonomous merge flow (CI + Gemini green → merge).
- **Disagrees** (unresolved architectural concern): keep it critical, flag the specific concern to Rick. Rick has final say, but Claude's objection is on record.

#### Non-critical → Critical escalation

If a major issue surfaces during a non-critical PR review:
- **Can be deferred:** open a GitHub issue, add `// TODO: #<issue>`, finish and merge the PR as non-critical.
- **Must be fixed in this PR:** update the description to `**PR Classification:** CRITICAL`, assign Rick as reviewer, notify in chat.

#### When Rick says "keep working based on the plan"
Claude applies the non-critical workflow and works autonomously through `WORK_PLAN.md` — open PR → CI + Gemini green → merge → next task — until a critical decision point is reached, then pauses and notifies Rick.

| PR type | Author | Assigned reviewer | Merge |
|---------|--------|-------------------|-------|
| Non-critical (Claude) | `nagasawa94` | none | Claude merges when CI + Gemini pass |
| Critical (Claude) | `nagasawa94` | `siliconimaginations` | Rick must approve |
| Any (Rick) | `siliconimaginations` | `nagasawa94` | Claude reviews in chat; Rick merges |

**Merging without CI green and a passed Gemini review is a process violation.**

#### Handling difficult issues during autonomous work

When Claude encounters a genuinely hard problem (platform incompatibility, ambiguous API, unclear design trade-off):

1. Open a GitHub issue describing the problem, what was tried, and what help or decision is needed.
2. Notify Rick in chat with the issue link.
3. Claude may work around the issue or pause — judgement call based on whether it is on the critical path.
4. When the issue is resolved (by Claude or Rick), close the issue with a comment explaining the resolution.

#### Determining the next task

After every merged PR, Claude must follow this sequence to decide what to work on next:

1. **Check the GitHub Projects board** at `https://github.com/users/siliconimaginations/projects/2`.
2. Take the highest-priority item in **This Sprint** (or **In Progress**) that is not blocked. `priority/P0` items always take precedence and interrupt the current sprint if present.
3. If the board is empty or all items are blocked, run the weekly triage (see `docs/process/tech-debt-cadence.md §1`) and surface the result to Rick.
4. Announce the next planned task in chat before starting — Rick can override the selection.

**Do not rely on `WORK_PLAN.md` alone** to determine next steps. The board is the authoritative queue; `WORK_PLAN.md` is the long-range roadmap. Rick may re-prioritise board items at any time, so always read the board fresh after each merge.

When choosing between two items of equal priority, prefer:
- An item that unblocks other items over one that stands alone
- A tech-debt item over a new feature if the debt is on the critical path
- A smaller item (≤ 200 lines) if a larger one would produce an oversized PR without a split plan

---

## 10. Definition of Done

A feature is **done** when:
- [ ] Design doc merged to `main`
- [ ] Implementation PR approved and merged
- [ ] CI passes (build + lint + tests)
- [ ] Coverage targets met
- [ ] No unresolved review comments
- [ ] `WORK_PLAN.md` stage updated if the feature completed a stage milestone

---

## 11. PR Size Policy

**Recommended**: < 400 lines changed (excluding generated files and lock files).
**Hard limit**: 1 000 lines changed. A PR exceeding this must be split before review starts — no exceptions.

### How to split a large module

A single module commonly maps to 3–4 sequential PRs:

| PR | Contents | Approx. size |
|----|----------|-------------|
| 1 | Domain model + persistence entity + repository | ≤ 200 lines |
| 2 | Service layer + preset/factory code | ≤ 300 lines |
| 3 | Controller + DTOs | ≤ 200 lines |
| 4 | Tests | ≤ 300 lines |

Each PR in the sequence must pass CI and be merged before the next one opens. Use `// TODO: #<issue>` stubs to compile without the later pieces.

### Claude-specific rule

Before starting implementation of any module, Claude must verify the total estimated line count against these limits and propose a split plan in chat if the estimate exceeds 400 lines. Raising the concern **after** coding is a process violation.

---

## 12. Tech Debt & Coverage Process

See [`docs/process/tech-debt-cadence.md`](docs/process/tech-debt-cadence.md) for the full weekly cadence.

**Labels** (applied on every new issue):

| Label | Meaning |
|-------|---------|
| `tech-debt` | Refactor, cleanup, design debt |
| `performance` | Measurable speed or resource improvement |
| `coverage` | Test coverage gap |
| `docs` | Stale or missing documentation |
| `ci` | Pipeline or tooling change |
| `priority/P0` | **Urgent** — active breakage, data loss, security issue, or blocker on another P1; must be resolved immediately. Do not use for normal sprint planning. |
| `priority/P1` | This sprint |
| `priority/P2` | Next 1–2 sprints |
| `priority/P3` | Backlog |

**Coverage thresholds** (enforced in CI):

| Layer | Minimum (overall) | Minimum (changed files on PR) |
|-------|-------------------|-------------------------------|
| Backend (JaCoCo) | 60% | 70% |
| Frontend (Vitest) | 60% | 70% |

Thresholds rise 5 pp per major stage milestone. The current values reflect Stage 3 (backend implementation in progress).
