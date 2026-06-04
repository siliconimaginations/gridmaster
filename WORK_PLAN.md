# Power Grid Game — Work Plan

## Overview

Educational power grid game with client-server architecture.
- **Backend**: Kotlin + Spring Boot + PowSyBl
- **Frontend**: Vite + React + TypeScript + Babylon.js
- **Repo**: GitHub monorepo (new)
- **Dev process**: Design doc → UX (where needed) → Engineering spec → Implementation → PR review → CI green → merge

---

## Repository Structure

```
power-grid-game/
├── backend/                    # Kotlin + Spring Boot
│   ├── src/main/kotlin/
│   │   ├── engine/             # PowSyBl physics wrappers
│   │   ├── game/               # Game loop, clock, events
│   │   ├── api/                # REST + WebSocket controllers
│   │   └── persistence/        # Session storage
│   └── src/test/kotlin/
├── frontend/                   # Vite + React + Babylon.js
│   ├── src/
│   │   ├── scene/              # Babylon.js 3D scene
│   │   ├── ui/                 # React panels, overlays
│   │   ├── state/              # Client game state (Zustand/Redux)
│   │   └── api/                # WebSocket + REST client
│   └── public/
│       └── assets/             # 3D meshes, textures
├── docs/
│   ├── engineering/            # Per-module engineering specs (.md)
│   └── ux/                     # UX design documents
├── .github/
│   ├── workflows/
│   │   ├── ci.yml              # Build + lint + test on PR
│   │   └── release.yml         # (future) deploy pipeline
│   └── PULL_REQUEST_TEMPLATE.md
├── docker-compose.yml          # Local dev: backend + DB
└── README.md
```

---

## Staged Work Plan

### Stage 0 — Foundation & Tooling
**Goal**: Repo live, local dev works end-to-end, CI running.

| Submodule | Description | Design Doc Needed |
|-----------|-------------|-------------------|
| Repo setup | GitHub repo, branch protection (main requires PR + review), monorepo structure | No |
| Backend scaffold | Spring Boot skeleton, PowSyBl dependency, health endpoint | No |
| Frontend scaffold | Vite + React + TS + Babylon.js skeleton, blank 3D canvas loads | No |
| WebSocket proof of concept | Backend pushes ping; frontend receives and logs | No |
| Docker Compose | Backend + PostgreSQL for local dev; one-command startup | No |
| GitHub Actions CI | Build, lint (ktlint + ESLint), unit test on every PR | No |
| PR template | Checklist: design doc linked, tests added, lint green | No |

**Exit criteria**: `docker-compose up` starts backend; frontend `npm run dev` renders blank Babylon canvas; CI passes on a sample PR.

---

### Stage 1 — Physics Engine Integration
**Goal**: PowSyBl wrappers tested and exposed via API; IEEE 14-bus runs power flow.

| Submodule | Description | Design Doc |
|-----------|-------------|------------|
| Network model | IIDM network builder, bus/line/transformer/gen/load entities | `docs/engineering/01-network-model.md` |
| Power flow adapter | AC load flow wrapper, result DTO (flows, voltages, losses) | `docs/engineering/02-power-flow.md` |
| Contingency runner | N-1 analysis, result aggregation | `docs/engineering/03-contingency.md` |
| OPF / dispatch | Economic dispatch, unit commitment wrappers | `docs/engineering/04-dispatch.md` |
| Physics API | REST endpoints: GET network state, POST command (set gen output, trip line) | `docs/engineering/05-physics-api.md` |
| Test fixtures | IEEE 14-bus and IEEE 39-bus network files for testing | No |

**Exit criteria**: `POST /api/game/powerflow` on IEEE 14-bus returns correct flows; N-1 returns critical contingencies; all physics unit tests green.

---

### Stage 2 — Game Engine Core
**Goal**: Server-authoritative game loop running; player sessions persisted; WebSocket streaming state.

| Submodule | Description | Design Doc |
|-----------|-------------|------------|
| Game session model | Session entity, user auth (JWT, single user initially), PostgreSQL persistence | `docs/engineering/06-session-model.md` |
| Game clock | Tick engine, configurable speed (1×–100×), pause/resume, auto-slow on events | `docs/engineering/07-game-clock.md` |
| Event engine | Weather, economic, policy events; trigger conditions; effect handlers | `docs/engineering/08-event-engine.md` |
| Command handler | Player action → validation → physics update → new state | `docs/engineering/09-command-handler.md` |
| WebSocket state stream | Server pushes `GameStateUpdate` each tick; client merges | `docs/engineering/10-websocket-protocol.md` |
| Alert system | Event log, severity ranking, SCADA-style alarm feed | Part of 08 |

**Exit criteria**: Game clock ticks, events fire on schedule, player can send dispatch command and receive updated grid state over WebSocket within one tick.

---

### Stage 3 — 3D Visualization
**Goal**: Babylon.js scene renders a live grid; power flow is animated; React UI panels are wired to state.

| Submodule | Description | Design Doc / UX Doc |
|-----------|-------------|---------------------|
| Scene foundation | Babylon.js setup, toon shader, isometric camera, terrain | `docs/ux/scene-design.md` |
| Component meshes | Bus (substation), line, transformer, generator, load — cartoon sprites/meshes | `docs/ux/component-visual-guide.md` |
| Power flow animation | Particle flow along lines; brightness/thickness = loading level; overload = red pulse | `docs/engineering/11-flow-animation.md` |
| React UI shell | Panel layout: left control panel, right alert feed, bottom time axis | `docs/ux/ui-layout.md` |
| Time axis control | Scrubable bar, speed selector, clock display, forecast window | Part of UI layout |
| Component inspector | Click a bus/line → detail popup (voltage, flow, rating %) | `docs/ux/inspector.md` |
| Alert feed panel | Chronological event log, severity icons, click-to-focus-on-map | Part of UI layout |

**Exit criteria**: IEEE 14-bus renders on screen; lines animate flow correctly; clicking a generator opens its inspector; alerts appear in feed.

---

### Stage 4 — Tutorial Mode
**Goal**: 8-mission tutorial playable end-to-end; missions teach defined curriculum.

| Mission | Topic | Design Doc |
|---------|-------|------------|
| 1 | Grid anatomy — identify buses, lines, TX, gens, loads | `docs/engineering/tutorial/mission-01.md` |
| 2 | Power flow — MW flow from gen to load, voltage angles | `docs/engineering/tutorial/mission-02.md` |
| 3 | Operating limits — overload a line, observe consequences | `docs/engineering/tutorial/mission-03.md` |
| 4 | Generator dispatch — manually dispatch gens to meet load | `docs/engineering/tutorial/mission-04.md` |
| 5 | Contingency analysis — run N-1, identify critical contingencies | `docs/engineering/tutorial/mission-05.md` |
| 6 | Economic dispatch — use OPF, minimize cost | `docs/engineering/tutorial/mission-06.md` |
| 7 | Unit commitment — day-ahead schedule, commit/decommit units | `docs/engineering/tutorial/mission-07.md` |
| 8 | Dynamics & stability — frequency response, fault + restoration | `docs/engineering/tutorial/mission-08.md` |

Supporting submodules:

| Submodule | Description | Design Doc |
|-----------|-------------|------------|
| Mission framework | YAML-defined missions; objective tracker; pass/fail conditions | `docs/engineering/12-mission-framework.md` |
| Tutorial UI | Instruction card overlay, objective checklist, hint system, progress indicator | `docs/ux/tutorial-ui.md` |
| Tutorial network | Custom 14–20 bus fictional network designed for pedagogy | `docs/engineering/tutorial/network-design.md` |

**Exit criteria**: All 8 missions playable; each teaches its stated concept; progress persists across browser sessions.

---

### Stage 5 — Free Play Mode
**Goal**: Long-running campaign with organic grid growth, environment events, and multi-role player decisions.

| Submodule | Description | Design Doc |
|-----------|-------------|------------|
| Region unlock system | Demand pressure accumulates; threshold triggers unlock prompt; player invests to expand | `docs/engineering/13-region-unlock.md` |
| Environment event engine | Weather/economic/policy event schedules; randomized within realistic distributions | `docs/engineering/14-environment-events.md` |
| Long-term planning tools | Investment queue, N-1 checklist, load forecast panel, budget tracker | `docs/ux/planning-panel.md` |
| Economic dispatch UI | Day-ahead unit commitment workflow, fuel cost display, merit order view | `docs/ux/dispatch-ui.md` |
| Market/policy events | Card-based policy decisions ("accept renewable subsidy?"); carbon budget tracker | `docs/engineering/15-policy-events.md` |
| Free play network | Multi-regional seed network (~50 buses) with designed expansion zones | `docs/engineering/freeplay-network-design.md` |

**Exit criteria**: Free play session runs for simulated 1 year without crash; regions unlock naturally; at least 3 environment event types fire and affect grid.

---

### Stage 6 — Challenge Mode
**Goal**: Pre-loaded crisis scenarios, scored resolution.

| Submodule | Description | Design Doc |
|-----------|-------------|------------|
| Scenario framework | YAML scenario definitions; entry state loader; scoring engine | `docs/engineering/16-scenario-framework.md` |
| Challenge scenarios (v1) | Overload relief, blackout restoration, optimal dispatch race, N-1 violation resolution | `docs/engineering/challenge-scenarios-v1.md` |
| Score & results screen | Time-to-resolve, cost, reliability metrics; result card UI | `docs/ux/challenge-results.md` |

**Exit criteria**: At least 3 challenge scenarios completable with correct scoring.

---

### Stage 7 — Hardening & CI/CD Maturity
**Goal**: Reliable pipeline, test coverage, performance validated.

| Submodule | Description |
|-----------|-------------|
| Integration tests | Full game loop tests (clock → event → physics → WebSocket state) |
| Frontend E2E | Playwright tests for critical flows (tutorial mission 1–3, challenge launch) |
| Performance profiling | Verify <1000-bus power flow stays within tick budget |
| CI enhancements | Coverage reporting, E2E on PR, release tagging |
| Cloud deploy guide | Docker Compose → cloud-ready config (future) |

---

## Development Process Per Feature

```
1. UX design doc (if visual/interaction change)         → PR → review
2. Engineering design doc (docs/engineering/*.md)       → PR → review
3. Implementation (backend + frontend)                  → PR → CI → review → merge
4. Follow-up: integration test coverage                 → PR → CI → merge
```

**Branch naming**: `stage/<n>/<short-description>` (e.g., `stage/1/power-flow-adapter`)
**PR rules**: linked design doc, lint green, tests added, one reviewer approval required

---

## What I Need From You

To kick off Stage 0, I need:
1. **GitHub org/username** to create the repo under (or you create it and share URL)
2. **Confirmation on any auth approach** for the server-side sessions — simplest is a single hardcoded user initially, upgrade to proper auth later
3. **PostgreSQL vs SQLite** for local persistence — SQLite is simpler for dev-only; Postgres is more production-realistic
