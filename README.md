# GridMaster

![CI](https://github.com/siliconimaginations/gridmaster/actions/workflows/ci.yml/badge.svg) ![CI Speed](.github/badges/ci-speed.svg)
![Backend Coverage](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/jacoco.svg) ![Frontend Coverage](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/vitest.svg) ![E2E Coverage](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/e2e-coverage.svg) ![E2E Pass Rate](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/e2e-pass.svg)

An educational power grid simulation game. Players learn how real power grids are operated and planned — from reading power flows and dispatching generators, to contingency analysis, economic dispatch, and long-term expansion planning.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Backend | Kotlin + Spring Boot 3 |
| Physics engine | PowSyBl (AC power flow, contingency, OPF) |
| Frontend | Vite + React + TypeScript + Babylon.js |
| Communication | WebSocket (game state) + REST (commands) |
| Persistence | SQLite (local dev) |
| CI/CD | GitHub Actions |

## Game Modes

- **Tutorial** — 5-step guided flow covering core power system concepts (observe, dispatch, handle a demand spike, pause/resume, complete)
- **Free Play** — long-running campaign; grid grows organically as society expands
- **Challenge** — drop into a pre-loaded crisis scenario, scored resolution

## Quick Start (local dev)

### Prerequisites
- JDK 21+
- Node.js 20+
- Docker + Docker Compose

### Backend
```bash
cd backend
./gradlew bootRun
```
Backend starts on `http://localhost:8080`.

### Frontend
```bash
cd frontend
npm install
npm run dev
```
Frontend starts on `http://localhost:5173`.

### All-in-one (Docker)
```bash
docker-compose up
```

## Project Structure

```
gridmaster/
├── backend/          # Kotlin + Spring Boot + PowSyBl
├── frontend/         # Vite + React + Babylon.js
├── docs/
│   ├── engineering/  # Per-module engineering design specs
│   └── ux/           # UX design documents
├── scripts/          # Local dev utilities (lint.sh)
└── .github/
    └── workflows/    # CI/CD pipelines
```

## Contributing

See [ENGINEERING_PRINCIPLES.md](ENGINEERING_PRINCIPLES.md) — all contributors (human and AI) follow the same design-before-code workflow.

Work plan and stage breakdown: [WORK_PLAN.md](WORK_PLAN.md).
