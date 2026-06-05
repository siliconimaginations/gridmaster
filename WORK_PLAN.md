# Power Grid Game — Work Plan

## Overview

Educational power grid game with client-server architecture.
- **Backend**: Kotlin + Spring Boot + PowSyBl
- **Frontend**: Vite + React + TypeScript + Babylon.js 7
- **Repo**: `github.com/siliconimaginations/gridmaster` (private)
- **Dev process**: UX doc → Engineering spec → Implementation → PR → CI → review → merge

---

## Progress Summary

| Stage | Name | Status |
|-------|------|--------|
| 0 | Foundation & Tooling | ✅ Complete |
| 1 | Physics Engine — design docs | ✅ Complete |
| 2 | UX Design Docs | ✅ Complete |
| 3 | Game Engine Core — implementation | 🔜 Next |
| 4 | 3D Visualization — implementation | 🔜 After 3 |
| 5 | Tutorial Mode | 🔲 Planned |
| 6 | Free Play Mode | 🔲 Planned |
| 7 | Challenge Mode | 🔲 Planned |
| 8 | Hardening & CI/CD Maturity | 🔲 Planned |

---

## Repository Structure

```
gridmaster/
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
│   │   ├── ui/                 # React HUD panels, toasts, overlays
│   │   ├── state/              # Client game state (Zustand)
│   │   └── api/                # WebSocket + REST client
│   └── public/
│       └── assets/             # 3D meshes (.glb), textures
├── docs/
│   ├── engineering/            # Per-module engineering specs (.md)
│   └── ux/                     # UX design documents
├── .github/
│   ├── workflows/
│   │   └── ci.yml              # Build + lint + test on PR
│   └── PULL_REQUEST_TEMPLATE.md
├── docker-compose.yml
└── scripts/lint.sh             # ktlint 1.2.1 + ESLint, run before every push
```

---

## Stage 0 — Foundation & Tooling ✅

Repo live, CI running, local dev works end-to-end.

- GitHub repo + branch protection (main requires PR + review)
- Spring Boot skeleton, health endpoint, PowSyBl dependency (BOM `2025.0.2`)
- Vite + React + TS + Babylon.js skeleton, blank 3D canvas loads
- SQLite persistence for local dev (designed to swap to Postgres)
- Single-user auth (hardcoded for now; upgrade to JWT in Stage 5+)
- GitHub Actions CI: ktlint + ESLint + unit tests on every PR
- `scripts/lint.sh` for local pre-push checks

---

## Stage 1 — Engineering Design Docs ✅

All 10 backend engineering specs written and merged.

| Doc | Module |
|-----|--------|
| `docs/engineering/01-network-model.md` | IIDM network builder, bus/line/gen/load entities |
| `docs/engineering/02-power-flow.md` | AC load flow wrapper, result DTOs |
| `docs/engineering/03-contingency-analysis.md` | N-1 analysis, async background runner |
| `docs/engineering/04-dispatch.md` | Economic dispatch (LP) + Unit commitment (MIP/OR-Tools) |
| `docs/engineering/05-physics-api.md` | REST endpoints: network state, commands |
| `docs/engineering/06-session-model.md` | Session entity, SQLite persistence |
| `docs/engineering/07-game-clock.md` | Tick engine, speed 1×–100×, pause/resume |
| `docs/engineering/08-event-engine.md` | Weather/economic/policy events, effect handlers |
| `docs/engineering/09-command-handler.md` | Player action → validate → physics → new state |
| `docs/engineering/10-websocket-protocol.md` | Server-push GameStateUpdate each tick |

Key decisions baked into the specs:
- PowSyBl BOM `com.powsybl:powsybl-dependencies:2025.0.2`
- OR-Tools for UC MIP (SCIP/CBC) and LP dispatch
- No DC fallback on AC divergence — `NETWORK_FAILURE` event raised
- Distributed slack bus; `gridMinutesPerTick = 10` (fixed)
- N-1 runs async every 6 ticks, debounced
- `CommandResult` with `commandOutcomes` list (unified, no batch variant)
- Region = annotation on unified IIDM network (not a physical network split)

---

## Stage 2 — UX Design Docs ✅

All 9 UX documents written and merged.

| Doc | Covers |
|-----|--------|
| `docs/ux/01-main-layout.md` | Full-screen layout, HUD structure, map composition rules |
| `docs/ux/02-component-inspector.md` | Per-element popup cards (generator, line, bus, city, transformer) |
| `docs/ux/03-alert-toasts.md` | Toast notification system, severity levels, auto-dismiss |
| `docs/ux/04-time-axis.md` | Clock controls, speed multipliers, day-ahead timeline strip |
| `docs/ux/05-tutorial-overlay.md` | Spotlight, instruction card, 8-mission flow |
| `docs/ux/06-dispatch-panel.md` | Real-time merit order table + 24 h UC schedule grid |
| `docs/ux/07-planning-panel.md` | Investment queue, N-1 table, 7-day demand/renewable forecast |
| `docs/ux/08-event-card.md` | Shared panel pattern: N-1, network failure, weather, fuel, policy |
| `docs/ux/09-scene-visual-spec.md` | Babylon.js art direction, toon shader, per-element visual spec |

Approved design direction:
- Township-style cartoon game, **not** industrial SCADA
- Full-screen Babylon.js isometric 3D scene; all UI as floating HUD overlays
- Top pill HUD (clock, load, price, health) + bottom HUD (speed controls, action buttons)
- Toasts stacked bottom-right; inspector popup per map element click
- Shared event panel pattern: coloured header + causal flow strip + 3 metric cards + option cards

---

## Stage 3 — Backend Implementation 🔜

Implement the physics engine and game engine core. Backend only; frontend runs against mocked or
stub state until Stage 4.

### 3a — Physics Engine

| Submodule | Design Doc | Branch pattern |
|-----------|-----------|----------------|
| Network model | `01-network-model.md` | `stage/3/network-model` |
| Power flow adapter | `02-power-flow.md` | `stage/3/power-flow` |
| Contingency runner | `03-contingency-analysis.md` | `stage/3/contingency` |
| Economic dispatch + UC | `04-dispatch.md` | `stage/3/dispatch` |
| Physics REST API | `05-physics-api.md` | `stage/3/physics-api` |
| Test fixtures | IEEE 14-bus + IEEE 39-bus IIDM files | `stage/3/test-fixtures` |

Exit criteria: `POST /api/game/powerflow` on IEEE 14-bus returns correct flows; N-1 returns
critical contingencies; all physics unit tests green.

### 3b — Game Engine Core

| Submodule | Design Doc | Branch pattern |
|-----------|-----------|----------------|
| Session model + persistence | `06-session-model.md` | `stage/3/session-model` |
| Game clock | `07-game-clock.md` | `stage/3/game-clock` |
| Event engine | `08-event-engine.md` | `stage/3/event-engine` |
| Command handler | `09-command-handler.md` | `stage/3/command-handler` |
| WebSocket state stream | `10-websocket-protocol.md` | `stage/3/websocket` |

Exit criteria: Game clock ticks at configured speed; events fire on schedule; player dispatch
command updates grid state; new state streams to client over WebSocket within one tick.

---

## Stage 4 — Frontend Implementation 🔜

3D scene, HUD, and all panels wired to live backend state. Backend from Stage 3 must be
minimally functional before wiring; scene scaffold can be built in parallel.

### 4a — Babylon.js Scene

| Submodule | UX Reference |
|-----------|-------------|
| Scene foundation | `09-scene-visual-spec.md` |
| Toon shader (cel-shade + outline pass) | `09-scene-visual-spec.md` |
| Isometric camera (pan + zoom) | `01-main-layout.md` |
| Terrain (heightmap, grass, river) | `09-scene-visual-spec.md` |
| Grid element meshes (procedural) | `09-scene-visual-spec.md` |
| Power flow particle animation | `01-main-layout.md` |
| LOD (far = icon sprite, near = mesh) | `09-scene-visual-spec.md` |
| City/town mesh LOD tiers | `09-scene-visual-spec.md` |

### 4b — React UI

| Submodule | UX Reference |
|-----------|-------------|
| Top HUD — pill badges | `01-main-layout.md` |
| Bottom HUD — speed controls | `04-time-axis.md` |
| Bottom HUD — contextual action buttons | `01-main-layout.md` |
| Day-ahead timeline strip | `04-time-axis.md` |
| Alert toast system | `03-alert-toasts.md` |
| Component inspector popup | `02-component-inspector.md` |
| Event card panel (shared pattern) | `08-event-card.md` |
| Dispatch panel (merit order + UC grid) | `06-dispatch-panel.md` |
| Planning panel (invest + N-1 + forecast) | `07-planning-panel.md` |

### 4c — State & API Layer

| Submodule | Notes |
|-----------|-------|
| Zustand store — game state | Mirrors server `GameStateUpdate` shape |
| WebSocket client (STOMP) | Subscribe to tick updates, merge into store |
| REST client | Session create/resume, one-off commands |
| Command dispatch | Player action → REST POST → optimistic UI update |

### 4d — Frontend Art Strategy

No external artists needed for MVP. Work in three phases:

| Phase | What | How |
|-------|------|-----|
| MVP | All grid element meshes (pylons, generators, substations) | Procedural Babylon.js geometry; toon shader makes them look intentional |
| Polish | Building variety for cities and towns | Free CC0 models from **Kenney.nl** (City Kit, Tiny Town packs) — drop-in `.glb` files |
| Textures | Terrain grass/dirt, water normal map | AI-generated (Midjourney/DALL-E) using prompts written alongside code |

Icons: written as SVG directly. UI animations: Framer Motion (React) + Babylon.js animation groups (scene).

Exit criteria: IEEE 14-bus renders on screen; lines animate power flow; clicking any element opens
its inspector; HUD updates every tick; dispatch panel opens and submits a command.

---

## Stage 5 — Tutorial Mode

8-mission guided campaign on a 14–20 bus fictional teaching network.

| Submodule | Notes |
|-----------|-------|
| Mission framework | YAML-defined missions; objective tracker; pass/fail conditions |
| Tutorial network | Custom 14–20 bus network designed for pedagogy |
| Tutorial overlay | Per `docs/ux/05-tutorial-overlay.md` (spotlight, instruction card, hint system) |
| Mission 1 — Grid anatomy | Click every element type |
| Mission 2 — Power flow | Trace generation to load |
| Mission 3 — Operating limits | Trigger overload, restore |
| Mission 4 — Generator dispatch | Manually meet demand |
| Mission 5 — Contingency analysis | Read N-1 risk table |
| Mission 6 — Economic dispatch | Merit order, minimize cost |
| Mission 7 — Unit commitment | Fill 24 h day-ahead schedule |
| Mission 8 — Dynamics & stability | Respond to sudden generator trip |

Exit criteria: All 8 missions completable; each teaches its stated concept; progress persists.

---

## Stage 6 — Free Play Mode

Long-running campaign with organic grid growth and multi-role decisions (~50 → ~500 buses).

| Submodule | Notes |
|-----------|-------|
| Region unlock system | Demand pressure accumulates → unlock prompt → player invests |
| Environment event engine | Randomized weather/economic/policy events |
| Planning panel | Per `docs/ux/07-planning-panel.md` — invest, N-1, forecast |
| Dispatch panel | Per `docs/ux/06-dispatch-panel.md` — day-ahead UC workflow |
| Policy event cards | "Accept renewable subsidy?" — card-based decisions |
| Free play seed network | Multi-regional ~50 bus network with expansion zones |

Exit criteria: Session runs 1 simulated year without crash; ≥3 environment event types fire; regions
unlock organically.

---

## Stage 7 — Challenge Mode

Pre-loaded crisis scenarios, scored resolution.

| Submodule | Notes |
|-----------|-------|
| Scenario framework | YAML definitions; entry state loader; scoring engine |
| Challenge scenarios v1 | Overload relief, blackout restoration, optimal dispatch race, N-1 resolution |
| Score & results screen | Time-to-resolve, cost, reliability metrics |

Exit criteria: ≥3 scenarios completable with correct scoring.

---

## Stage 8 — Hardening & CI/CD Maturity

| Submodule | Notes |
|-----------|-------|
| Integration tests | Full game loop: clock → event → physics → WebSocket state |
| Frontend E2E | Playwright: tutorial missions 1–3, challenge launch |
| Performance profiling | <1000-bus power flow within tick budget |
| CI enhancements | Coverage reporting, E2E on PR, release tagging |
| Cloud deploy guide | Docker Compose → cloud-ready config |

---

## Development Process Per Feature

```
1. UX design doc (if visual/interaction change)    → PR → review → merge
2. Engineering design doc                          → PR → review → merge
3. Implementation (backend or frontend)            → PR → CI green → review → merge
4. Integration test coverage                       → PR → CI green → merge
```

**Branch naming**: `stage/<n>/<short-description>`  
**PR rules**: linked design doc, lint green, tests added, one reviewer approval required  
**Lint**: run `bash scripts/lint.sh` before every push  
