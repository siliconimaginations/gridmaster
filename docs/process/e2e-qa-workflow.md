# E2E Testing & QA Workflow

**Status**: Active  
**Implements**: GitHub issue #96  
**Related**: `ENGINEERING_PRINCIPLES.md §7`, `tech-debt-cadence.md`

---

## Purpose

This document defines GridMaster's end-to-end (E2E) test coverage plan, how
E2E tests are wired into CI, and the QA process that governs milestone
sign-off before a stage is declared done.

E2E tests exercise the full stack — Spring Boot backend, WebSocket/STOMP
protocol, and the Babylon.js/React frontend — in a real browser. They
complement unit tests (which mock dependencies) and integration tests (which
exercise the backend in isolation) by catching regressions that only surface
when all three layers are running together.

---

## Scope

**In scope**
- E2E test scenario catalogue for the current game feature set
- Playwright as the E2E test runner (browser automation)
- GitHub Actions `e2e` workflow (separate from the fast CI jobs)
- Local E2E setup using `docker-compose`
- QA environment setup, QA approval gate, and QA feedback loop

**Out of scope**
- Performance/load testing (separate concern, Stage 8)
- Multiplayer/multi-client scenarios (not yet designed)
- Mobile or non-Chromium browser compatibility (deferred to Stage 7)

---

## E2E Feature Collection

Each scenario maps to a user-visible game behaviour. Scenarios are grouped
by subsystem and labelled `P0` (must pass before any release), `P1` (should
pass before stage milestone), or `P2` (good-to-have).

### Session Lifecycle

| ID | Scenario | Priority |
|----|----------|----------|
| SL-01 | App loads: blank canvas renders, no JS errors in console | P0 |
| SL-02 | `POST /api/sessions` creates a session; response contains valid JWT and `sessionId` | P0 |
| SL-03 | Frontend connects to WebSocket with JWT; first `GameStateUpdate` arrives within 3 s | P0 |
| SL-04 | Page reload reconnects WsClient and resubscribes automatically | P1 |
| SL-05 | Session persists across app restart (SQLite); rejoining resumes game clock | P1 |

### Game Clock & Tick Stream

| ID | Scenario | Priority |
|----|----------|----------|
| GC-01 | Clock advances at 1× speed; tick counter increments in the Top HUD | P1 |
| GC-02 | Pause command halts tick stream; resume restarts it | P1 |
| GC-03 | Speed change (1×→4×) causes the tick interval to shrink proportionally | P2 |

### Player Commands — Network Mutations

| ID | Scenario | Priority |
|----|----------|----------|
| CM-01 | Toggle generator OFF: `PlayerCommand` sent → WebSocket delivers `GameStateUpdate` with generator `p=0` | P0 |
| CM-02 | Toggle generator ON: reverse of CM-01 | P0 |
| CM-03 | Adjust generator setpoint: updated `targetP` reflected in next tick | P1 |
| CM-04 | Invalid command (unknown busId): backend returns `400`; UI shows toast error | P1 |

### Economic Dispatch

| ID | Scenario | Priority |
|----|----------|----------|
| ED-01 | Dispatch endpoint returns merit-order result for the IEEE 14-bus preset | P1 |
| ED-02 | All generators committed: total generation ≥ total load | P1 |

### Event Engine

| ID | Scenario | Priority |
|----|----------|----------|
| EV-01 | A generated event appears in the `GameStateUpdate.events` array | P1 |
| EV-02 | Player responds to an event; event is removed from the active list | P2 |

### HUD & UI Panels *(add as frontend features land)*

| ID | Scenario | Priority |
|----|----------|----------|
| HUD-01 | Top HUD shows clock, load, price, health values (non-zero after first tick) — ✅ implemented | P1 |
| HUD-02 | Dispatch panel renders merit-order table with ≥ 1 row — ✅ implemented (PR #155) | P2 |
| HUD-03 | Alert toast appears when a P0 event fires | P2 |

---

## E2E Tech Stack

### Playwright

Playwright is the E2E framework.  Key reasons:
- First-class TypeScript support — consistent with the frontend toolchain
- Headless Chromium runs cleanly in GitHub Actions `ubuntu-22.04`
- `page.waitForSelector` and network-idle waits handle Babylon.js canvas boot
- Built-in WebSocket traffic inspection via `page.on('websocket', …)`

Test files live in `frontend/e2e/` (Playwright convention).  They import
helpers from `frontend/e2e/helpers/` (session creation, WS message
listeners).

### docker-compose

`docker-compose.yml` (already in repo root) is the canonical way to spin up
the full stack for E2E.  The E2E GitHub Actions job extends it with an
`e2e` service profile.

```
docker compose up --wait   # starts backend + waits for health check
npm run dev --prefix frontend   # Vite dev server on :5173 (or built dist on :4173)
npx playwright test
```

For CI, the backend is started from source (`./gradlew bootRun &`) to avoid
a Docker layer cache miss on every run.

---

## CI Workflow — `e2e.yml`

E2E tests run in a **separate workflow** (`e2e.yml`), not inside `ci.yml`.
Rationale: Playwright + a running JVM add ~3–5 min to wall-clock time.
Separating the workflow keeps the fast PR gate (`ci.yml`, target < 3.5 min)
intact.

### Trigger

```yaml
on:
  push:
    branches: [main]          # after every merge to main
  workflow_dispatch:           # manual trigger for QA runs
  # pull_request:              # enable in Stage 7 when suite is stable
```

E2E runs on `main` pushes, not on every PR, until the suite is stable (Stage 7).
PRs that are E2E-blocked can use `workflow_dispatch` to trigger a run manually.

### Job structure

```
setup-stack
  ├─ checkout
  ├─ set up JDK 21 + Node 20
  ├─ build backend (./gradlew build -x test)
  ├─ start backend (./gradlew bootRun &) → wait for /actuator/health
  ├─ build frontend (npm run build --prefix frontend)
  ├─ start preview server (npx vite preview --prefix frontend &)
  └─ run Playwright (npx playwright test)
       ├─ upload test-results/ as artifact on failure
       └─ upload playwright-report/ as artifact always
```

### Failure handling

- On failure, the Playwright HTML report and screenshots are uploaded as
  GitHub Actions artifacts (`retention-days: 14`).
- E2E failures on `main` create a GitHub issue labelled `ci` + `P1` if none
  already exists for that scenario ID (Claude automation task).
- E2E does **not** block PR merge until Stage 7.

---

## Running E2E Locally

```bash
# 1. Start the backend
cd backend
export JAVA_HOME=/tmp/jdk21 && export PATH=$JAVA_HOME/bin:$PATH
./gradlew bootRun &

# 2. Start the frontend dev server
cd frontend && npm install && npm run dev &

# 3. Run all E2E tests
cd frontend
npx playwright install chromium   # first time only
npx playwright test

# 4. Run a single scenario
npx playwright test --grep "SL-03"

# 5. Debug interactively
npx playwright test --ui
```

Playwright config lives at `frontend/playwright.config.ts`.  The base URL
defaults to `http://localhost:5173`; override with `BASE_URL` env var.

---

## QA Workflow

QA is a milestone-level gate run at the end of each stage before the stage
is declared done.

### Environment Setup

```bash
# One-command full stack (recommended for QA)
docker compose up --build --wait

# Seed the IEEE 14-bus preset (if not auto-loaded)
curl -s -X POST http://localhost:8080/api/sessions \
  -H "Content-Type: application/json" \
  -d '{"userId": "00000000-0000-0000-0000-000000000001", "preset": "ieee14"}'
```

Requirements: Docker Desktop, JDK 21, Node 20, Chrome/Chromium.

### QA Test Execution

1. Run the Playwright suite: `npx playwright test` (all P0 + P1 scenarios).
2. Manually exercise P2 scenarios listed in the feature collection above.
3. Record results in a GitHub comment on the milestone issue using the table
   template below.

### QA Results Template

```
## QA Run — Stage N

| Scenario | Result | Notes |
|----------|--------|-------|
| SL-01    | ✅ Pass |       |
| SL-02    | ✅ Pass |       |
| …        |        |       |

**Overall**: PASS / FAIL  
**Tester**: @siliconimaginations  
**Date**: YYYY-MM-DD  
**Commit**: abc1234
```

### QA Approval Gate

- QA is approved when all P0 and P1 scenarios pass and no open P1 issues
  remain in the current sprint.
- Rick (`@siliconimaginations`) approves by adding the `qa-approved` label
  to the milestone tracking issue.
- Claude may not advance to the next stage until `qa-approved` is set.

### QA Feedback Process

When QA reveals a defect:

1. Open a GitHub issue titled `qa: <short description> — <scenario ID>`.
2. Apply labels: `bug` + priority (`P1` if it blocks a P0 scenario, `P2`
   otherwise).
3. Add the issue to the **This Sprint** board column.
4. Claude picks it up via the normal board workflow (autonomous for non-critical
   fixes, critical flag for anything that touches game mechanics or API design).
5. After the fix merges, re-run the affected scenario and update the QA
   results comment.

---

## Design Decisions

1. **Playwright over Cypress**: Playwright has first-class WebSocket support
   (`page.on('websocket', …)`) which is essential for testing the STOMP
   tick stream. Cypress does not intercept WebSocket frames natively.

2. **Separate `e2e.yml` workflow, not part of `ci.yml`**: Keeps the PR gate
   fast. E2E will be promoted to a required PR check in Stage 7 once the
   suite is stable and the runtime is predictable.

3. **E2E tests in `frontend/e2e/`**: Co-locating with the frontend (rather
   than a top-level `e2e/` folder) keeps Playwright config, `package.json`
   devDependencies, and TypeScript types in one place. Backend is a
   dependency, not a host.

4. **No test database seeding via ORM**: E2E tests create sessions via the
   real `POST /api/sessions` REST endpoint, mirroring actual player flows.
   Direct DB manipulation would couple tests to the persistence schema.

5. **P0/P1/P2 priority levels**: P0 failures block any release; P1 failures
   block stage sign-off; P2 failures produce `P2` issues but do not block.
   This tiering avoids a false binary pass/fail for an evolving test suite.

---

## Open Questions

| # | Question | Owner | Target |
|---|----------|-------|--------|
| 1 | Should E2E be a required PR check starting Stage 5 (Tutorial) or Stage 7 (Hardening)? | Rick | Stage 5 planning |
| 2 | Do we need a separate `e2e` Spring profile (no event engine, deterministic tick) or is the real stack sufficient? | Claude | Stage 5 |
| 3 | Visual regression testing (screenshot comparison for Babylon.js canvas)? | Rick | Stage 7 |
| 4 | Should `qa-approved` label be set by Rick manually or by a passing Playwright run on a release branch? | Rick | Stage 5 |
