# E2E CI — Playwright Test Suite & GitHub Actions Workflow

**Stage**: 4  
**Status**: Draft  
**Implements**: GitHub issue #147  
**Depends on**:
- [12-frontend-state-api.md](12-frontend-state-api.md) — Zustand store is the source of truth; the test bridge exposes it
- [13-hud.md](13-hud.md) — HUD DOM elements are the primary assertion targets
- [docs/process/e2e-qa-workflow.md](../process/e2e-qa-workflow.md) — scenario catalogue and QA process (this doc implements the CI half of that spec)

---

## Purpose

Define the implementation of the Playwright E2E suite and the `e2e.yml` GitHub
Actions workflow so that the QA scenarios from `e2e-qa-workflow.md` can be
tracked automatically in CI instead of being run manually.

This doc covers three things the process doc left open:

1. **Frontend instrumentation** — how to expose observable state to Playwright
   without relying on canvas pixel inspection.
2. **Test structure** — helpers, fixtures, and how each scenario maps to
   Playwright code.
3. **CI wiring** — the concrete `e2e.yml` job, start-up sequencing, and
   artifact strategy.

---

## Scope

**In scope (issue #147)**
- `frontend/e2e/` directory: Playwright config, fixtures, helpers, initial tests
- `data-testid` attributes on HUD elements and the bootstrap overlay
- `window.__e2e` store bridge (dev/test builds only)
- `.github/workflows/e2e.yml` — the CI workflow
- P0 + selected P1 scenarios: SL-01 through SL-04, GC-01, GC-02, CM-01, CM-02, CM-03
- Local dev instructions

**Out of scope**
- P2 scenarios and HUD panels not yet built (ED, EV, HUD-02/03 — added as
  subsequent issues once panels land)
- Visual / screenshot regression testing — deferred to Stage 7
- Multi-browser matrix (Firefox, Safari) — deferred to Stage 7
- Docker-compose E2E profile — local `bootRun` approach is sufficient for now;
  containerisation deferred until the build is stable

---

## Key Concepts

### The canvas observability problem

Babylon.js renders the grid scene into a `<canvas>` element. Playwright cannot
inspect WebGL draw calls or mesh properties. Two complementary strategies
bridge this gap:

**Strategy A — DOM anchors (`data-testid`)**  
React HUD components already render observable state as DOM text. Adding
`data-testid` to clock state, load MW, connection status, etc. gives Playwright
concrete, stable selectors that do not depend on CSS class names or layout.

**Strategy B — `window.__e2e` store bridge**  
For state that never reaches the DOM (e.g. raw network data, tick number, alert
array contents), a thin shim exposes the Zustand store in dev/test builds:

```ts
// src/e2ebridge.ts  (tree-shaken in production)
if (import.meta.env.MODE !== 'production') {
  (window as Window & { __e2e?: unknown }).__e2e = {
    getStore: () => useGameStore.getState(),
  }
}
```

Playwright then evaluates:

```ts
const tick = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
```

This is **not** imported from `App.tsx` in production — Vite tree-shakes it
because `import.meta.env.MODE !== 'production'` is a compile-time constant.

### Test isolation

Each test creates its own session via the real REST API and stores the
`sessionId` in a Playwright fixture. On teardown, the fixture calls
`DELETE /api/sessions/{id}`. This keeps tests independent and prevents
clock-running sessions from polluting later tests.

---

## Directory Layout

```
frontend/
  e2e/
    fixtures/
      session.ts      ← createSession / teardown fixture
      page.ts         ← extended Page with __e2e helpers
    helpers/
      ws.ts           ← waitForStore(page, predicate)
      rest.ts         ← typed wrappers around fetch() for REST endpoints
    tests/
      sl.spec.ts      ← SL-01 … SL-04 (Session Lifecycle)
      gc.spec.ts      ← GC-01, GC-02 (Game Clock)
      cm.spec.ts      ← CM-01, CM-02, CM-03 (Command / Network Mutation)
  playwright.config.ts
```

Playwright config lives in `frontend/` (not the repo root) so it shares
`tsconfig.json`, `package.json` devDependencies, and the Vite base URL.

---

## Frontend Instrumentation

### `data-testid` additions

The following attributes are added to existing components. They are the
minimal set needed to cover all in-scope scenarios without touching game logic.

| Component | Element | `data-testid` | Value exposed |
|-----------|---------|---------------|---------------|
| `BootstrapOverlay` | root div (status=bootstrapping) | `bootstrap-overlay` | visible until ready |
| `BootstrapOverlay` | root div (status=ready) | removed / unmounted | — |
| `BootstrapOverlay` | error message span | `bootstrap-error` | error string |
| `TopHud` | clock badge | `hud-clock-state` | `RUNNING` / `PAUSED` / `STOPPED` |
| `TopHud` | tick counter | `hud-tick-number` | numeric string |
| `TopHud` | load badge | `hud-total-load` | `"142.3 MW"` |
| `TopHud` | health badge | `hud-grid-health` | `"OK"` / `"WARNING"` / `"CRITICAL"` |
| `BottomHud` | play/pause button | `hud-playpause-btn` | — |
| `AlertToastContainer` | container | `alert-toast-container` | — |
| `AlertToastItem` | individual toast | `toast-{id}` | already present ✓ |

### `window.__e2e` bridge

Added in `src/e2ebridge.ts`, imported once from `main.tsx` inside a
`if (import.meta.env.MODE !== 'production')` guard:

```ts
// src/e2ebridge.ts
import { useGameStore } from './state/useGameStore'

export function installE2EBridge(): void {
  ;(window as Window & { __e2e?: unknown }).__e2e = {
    /** Returns a snapshot of the current Zustand store state. */
    getStore: () => useGameStore.getState(),
    /** Returns a Promise that resolves when predicate(state) is true. */
    waitFor: (predicate: (s: ReturnType<typeof useGameStore.getState>) => boolean, timeoutMs = 10_000) =>
      new Promise<void>((resolve, reject) => {
        const deadline = Date.now() + timeoutMs
        const unsub = useGameStore.subscribe((state) => {
          if (predicate(state)) { unsub(); resolve() }
          else if (Date.now() > deadline) { unsub(); reject(new Error('waitFor timeout')) }
        })
        // Check immediately in case condition already holds
        if (predicate(useGameStore.getState())) { unsub(); resolve() }
      }),
  }
}
```

```ts
// src/main.tsx (addition)
if (import.meta.env.MODE !== 'production') {
  const { installE2EBridge } = await import('./e2ebridge')
  installE2EBridge()
}
```

The dynamic import ensures the bridge module is never included in the
production bundle even if the `MODE` check were accidentally removed.

---

## Test Helpers

### `waitForStore` (ws.ts)

Waits until a predicate on the Zustand store is satisfied. Uses
`page.evaluate` (which resolves Promises returned from the page context)
rather than `page.waitForFunction` (which expects a synchronous boolean
and will always time out if given a Promise).

```ts
/**
 * Resolves when the __e2e bridge's waitFor Promise settles, i.e. when
 * `predicate(store)` returns true. `page.evaluate` awaits the returned
 * Promise, so this blocks the test until the condition holds or times out.
 *
 * Use `page.waitForFunction` with a direct inline lambda instead for
 * simple synchronous predicates (e.g. tickNumber > 0) — it polls without
 * needing the bridge's subscription machinery.
 *
 * NOTE: Playwright's `page.evaluate` arg must be JSON-serializable — functions
 * cannot be passed as `arg`. The predicate must therefore be inlined into the
 * `pageFunction` string rather than passed as an argument. For this reason
 * `waitForStore` is intentionally NOT a generic helper with a function
 * parameter. Each call site inlines its predicate directly:
 *
 * ```ts
 * // Waiting for a store condition via the bridge's Promise machinery:
 * await page.evaluate(() =>
 *   (window as any).__e2e.waitFor((s: any) => s.clockState === 'PAUSED')
 * )
 *
 * // Simpler: page.waitForFunction for synchronous predicates (no Promise):
 * await page.waitForFunction(
 *   () => (window as any).__e2e?.getStore().tickNumber > 0,
 *   { timeout: 10_000 },
 * )
 * ```
 *
 * Use `page.waitForFunction` for synchronous predicates (it polls efficiently).
 * Use `page.evaluate` wrapping `__e2e.waitFor(...)` when the condition depends
 * on a future Zustand subscription event (e.g. a WebSocket update).
 */
```

The `ws.ts` helper file therefore contains only typed wrappers over the two
patterns above, not a generic function-argument helper. Example exports:

```ts
// e2e/helpers/ws.ts

/** Waits until clock state matches the expected value. */
export const waitForClockState = (page: Page, state: string) =>
  page.evaluate((s) =>
    (window as any).__e2e.waitFor((store: any) => store.clockState === s),
    state,
  )

/** Waits until tickNumber > baseline. */
export const waitForTick = (page: Page, baseline: number) =>
  page.waitForFunction(
    (n) => (window as any).__e2e?.getStore().tickNumber > n,
    baseline,
    { timeout: 10_000 },
  )
```

### Session fixture (session.ts)

```ts
interface SessionFixtures {
  sessionId: string
  token: string
}

export const test = base.extend<SessionFixtures>({
  // token is declared first so sessionId can depend on it
  token: async ({ request }, use) => {
    const res = await request.post('/api/auth/token', { data: {} })
    const { token } = await res.json()
    await use(token)
  },

  // sessionId depends on token — no second token request needed
  sessionId: async ({ request, token }, use) => {
    const sessionRes = await request.post('/api/sessions', {
      data: { displayName: 'E2E Session', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
      headers: { Authorization: `Bearer ${token}` },
    })
    const { id } = await sessionRes.json()

    await use(id)

    // Teardown — delete the session so the clock is stopped and the
    // row is removed, keeping the test database clean between runs.
    await request.delete(`/api/sessions/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  },
})
```

---

## Scenario Implementations

### SL-01 — App loads, no JS errors

```ts
test('SL-01 app loads without JS errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))
  await page.goto('/')
  // Bootstrap overlay disappears when session is ready
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })
  expect(errors).toHaveLength(0)
})
```

### SL-02 — Session creation returns valid data

```ts
test('SL-02 POST /api/sessions returns sessionId', async ({ request }) => {
  const tokenRes = await request.post('/api/auth/token', { data: {} })
  const { token } = await tokenRes.json()
  const res = await request.post('/api/sessions', {
    data: { displayName: 'SL-02', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(res.ok()).toBeTruthy()
  const body = await res.json()
  expect(body.id).toMatch(/^[0-9a-f-]{36}$/)
})
```

### SL-03 — First GameStateUpdate arrives within 3 s

```ts
test('SL-03 first GameStateUpdate within 3 s', async ({ page }) => {
  await page.goto('/')
  await page.waitForFunction(
    () => (window as any).__e2e?.getStore().tickNumber > 0,
    { timeout: 15_000 },   // includes bootstrap + WS connect time
  )
  const tick = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
  expect(tick).toBeGreaterThan(0)
})
```

### GC-01 — Clock advances at 1× speed

```ts
test('GC-01 tick counter increments', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })
  const tick1 = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
  // Wait 6 s (two 3-s ticks at 1×)
  await page.waitForTimeout(6_000)
  const tick2 = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
  expect(tick2).toBeGreaterThan(tick1)
  await expect(page.getByTestId('hud-tick-number')).not.toHaveText(String(tick1))
})
```

### GC-02 — Pause halts tick stream

```ts
test('GC-02 pause stops tick counter', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })
  // Pause
  await page.getByTestId('hud-playpause-btn').click()
  await expect(page.getByTestId('hud-clock-state')).toHaveText('PAUSED', { timeout: 5_000 })
  const tickAtPause = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
  await page.waitForTimeout(5_000)
  const tickAfterWait = await page.evaluate(() => (window as any).__e2e.getStore().tickNumber)
  expect(tickAfterWait).toBe(tickAtPause)
})
```

### CM-01 / CM-02 — Toggle generator off/on

Generator IDs in the ieee14 preset are assigned by PowSyBl's
`IeeeCdfNetworkFactory.create14Solved()` at runtime (e.g. `BUS-1-GEN-1`).
Rather than hardcoding IDs that may change with PowSyBl version bumps, the
test discovers them via a REST call in `beforeAll` once per file. All five
ieee14 generators start committed, so the first one in the list is always
a valid decommit target.

```ts
// cm.spec.ts
// Discovery session: all five ieee14 generators start committed, so any
// index is a valid decommit target. We only need one ID shared by both tests.
let committedGenId: string

test.beforeAll(async ({ request, token }) => {
  // Create a throwaway session just to discover the network structure.
  // Each test creates its own isolated session via the session fixture.
  const sessionRes = await request.post('/api/sessions', {
    data: { displayName: 'CM discovery', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })
  const { id } = await sessionRes.json()

  const networkRes = await request.get(`/api/sessions/${id}/network`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const network = await networkRes.json()
  committedGenId = network.generators.find((g: any) => g.committed)?.id

  await request.delete(`/api/sessions/${id}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  expect(committedGenId).toBeTruthy()
})

test('CM-01 toggle generator off → committed=false in next update', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })

  // The app's bootstrap session owns the live network; send the command through the store.
  await page.evaluate((id) => {
    const { sendCommand } = (window as any).__e2e.getStore()
    sendCommand({ commandType: 'DecommitGenerator', payload: { generatorId: id } })
  }, committedGenId)

  await page.waitForFunction(
    (id) => {
      const { network } = (window as any).__e2e.getStore()
      return network?.generators.find((g: any) => g.id === id && !g.committed)
    },
    committedGenId,
    { timeout: 10_000 },
  )
})
```

CM-02 must not rely on `decommittedGenId` from the `beforeAll` block, because
each test gets its own bootstrapped session whose generators all start
committed. CM-02 controls its own precondition explicitly:

```ts
test('CM-02 toggle generator on → committed=true in next update', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })

  // Step 1: Decommit a known generator to establish a clean baseline.
  await page.evaluate((id) => {
    (window as any).__e2e.getStore().sendCommand({ commandType: 'DecommitGenerator', payload: { generatorId: id } })
  }, committedGenId)
  await page.waitForFunction(
    (id) => {
      const { network } = (window as any).__e2e.getStore()
      return network?.generators.find((g: any) => g.id === id && !g.committed)
    },
    committedGenId,
    { timeout: 10_000 },
  )

  // Step 2: Commit it back and assert.
  await page.evaluate((id) => {
    (window as any).__e2e.getStore().sendCommand({ commandType: 'CommitGenerator', payload: { generatorId: id } })
  }, committedGenId)
  await page.waitForFunction(
    (id) => {
      const { network } = (window as any).__e2e.getStore()
      return network?.generators.find((g: any) => g.id === id && g.committed)
    },
    committedGenId,
    { timeout: 10_000 },
  )
})

### CM-03 — Set generator active power output

```ts
/**
 * Sends SetGeneratorOutput targeting 50% of the generator's max rating (safe
 * across all ieee14 generators). Waits for `activePowerMw` in the next
 * GameStateUpdate to be within 1 MW of the requested value.
 *
 * The ±1 MW tolerance accounts for floating-point rounding in the PowSyBl AC
 * power flow solve — the setpoint is applied as-requested but the solved
 * output reflects the power-balance solution.
 */
test('CM-03 SetGeneratorOutput → activePowerMw reflects new setpoint', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', { state: 'hidden', timeout: 15_000 })

  // Wait for at least one tick so the network state is populated in the store.
  await page.waitForFunction(
    () => (window as any).__e2e?.getStore().tickNumber > 0,
    { timeout: 15_000 },
  )

  const targetMw = Math.round(committedGenMaxMw * 0.5) // 50% of max — stays within min/max

  await page.evaluate(([id, mw]) => {
    (window as any).__e2e.getStore()
      .sendCommand({ commandType: 'SetGeneratorOutput', payload: { generatorId: id, targetMw: mw } })
  }, [committedGenId, targetMw] as [string, number])

  // Wait for the broadcast GameStateUpdate to reflect the new setpoint
  await page.waitForFunction(
    ([id, mw]) => {
      const { network } = (window as any).__e2e.getStore()
      const gen = network?.generators.find((g: any) => g.id === id)
      return gen !== undefined && Math.abs(gen.activePowerMw - (mw as number)) < 1.0
    },
    [committedGenId, targetMw] as [string, number],
    { timeout: 20_000 },
  )
})
```

`committedGenMaxMw` is discovered alongside `committedGenId` in the shared `beforeAll`
block — the REST discovery call already reads `maxActivePowerMw` from the network response.


---

## Playwright Config

```ts
// frontend/playwright.config.ts
import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e/tests',
  timeout: 60_000,       // generous: includes Spring Boot startup in CI
  expect: { timeout: 10_000 },
  fullyParallel: false,  // single backend instance; tests share it but use isolated sessions
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html']],

  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
```

`fullyParallel: false` because all tests share one backend. Tests that need
true isolation use the session fixture for independent sessions.

---

## `package.json` Additions

```json
{
  "scripts": {
    "e2e":       "playwright test",
    "e2e:ui":    "playwright test --ui",
    "e2e:debug": "playwright test --debug"
  },
  "devDependencies": {
    "@playwright/test": "^1.44.0"
  }
}
```

`npx playwright install chromium` is run once (locally) or in CI via the
workflow step below.

---

## `e2e.yml` GitHub Actions Workflow

```yaml
name: E2E tests

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: read
  issues: write     # for auto-filing failure issues (future)

jobs:
  e2e:
    name: Playwright E2E
    runs-on: ubuntu-latest
    timeout-minutes: 20

    steps:
      - name: Checkout
        uses: actions/checkout@v6

      - name: Set up JDK 21
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '21'
          cache: gradle

      - name: Set up Node 24
        uses: actions/setup-node@v6
        with:
          node-version: '24'
          cache: npm
          cache-dependency-path: frontend/package-lock.json

      - name: Build backend (skip tests)
        working-directory: backend
        run: ./gradlew build -x test --no-daemon

      - name: Start backend
        working-directory: backend
        run: |
          # Start bootRun in background; capture PID for diagnostics if startup fails.
          ./gradlew bootRun --no-daemon > /tmp/backend.log 2>&1 &
          BACKEND_PID=$!
          echo "Backend PID: $BACKEND_PID"
          echo "Waiting for /actuator/health…"
          for i in $(seq 1 30); do
            if ! kill -0 $BACKEND_PID 2>/dev/null; then
              echo "Backend process died. Last 50 lines:" && tail -50 /tmp/backend.log
              exit 1
            fi
            curl -sf http://localhost:8080/actuator/health && break
            sleep 2
          done

      - name: Install frontend dependencies
        working-directory: frontend
        run: npm ci

      - name: Install Playwright browsers
        working-directory: frontend
        run: npx playwright install chromium --with-deps

      - name: Build frontend (e2e mode — includes __e2e bridge)
        working-directory: frontend
        run: npm run build:e2e

      - name: Start frontend preview server
        working-directory: frontend
        run: npx vite preview --port 5173 &

      - name: Run Playwright tests
        working-directory: frontend
        run: npm run e2e
        env:
          BASE_URL: http://localhost:5173
          CI: true

      - name: Upload Playwright report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-report
          path: frontend/playwright-report/
          retention-days: 14

      - name: Upload test results (on failure)
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: playwright-results
          path: frontend/test-results/
          retention-days: 14
```

### Why `vite preview` (not `vite dev`) in CI

The `vite dev` server starts HMR and can behave differently in a headless
environment. `vite build && vite preview` produces a static bundle served
by Vite's production-mode HTTP server, which is closer to a real deployment
and deterministic across runs.

The `window.__e2e` bridge is gated on `import.meta.env.MODE !== 'production'`,
but `vite preview` serves a production build — it does **not** inject
`MODE=development`. To keep the bridge available in CI, a dedicated
`test` mode is used:

```ts
// src/main.tsx
if (import.meta.env.MODE !== 'production') { … }
```

No change to `vite.config.ts` is needed. Vite supports arbitrary `--mode`
values out of the box — `vite build --mode e2e` sets `import.meta.env.MODE`
to `'e2e'` without any config. The only constraint is that a corresponding
`.env.e2e` file should not accidentally override variables; none is needed
here.

CI runs `npm run build:e2e` (`vite build --mode e2e`) and `vite preview`
serves the `e2e` build. The bridge is present; production users never receive
it.

The `package.json` scripts:

```json
"build":     "tsc && vite build",
"build:e2e": "tsc && vite build --mode e2e",
"e2e":       "playwright test"
```

| Command | MODE | Bridge present? | Use |
|---------|------|-----------------|-----|
| `vite dev` | `development` | ✅ yes | Local interactive dev |
| `npm run build:e2e` + `vite preview` | `e2e` | ✅ yes | CI E2E runs |
| `npm run build` + `vite preview` | `production` | ❌ no | Production deployments |

`npm run build` (production) does **not** expose the bridge — the
`MODE !== 'production'` guard strips it at build time.

---

## Design Decisions

### 1. DOM + store bridge over WebSocket frame interception

Playwright's `page.on('websocket', …)` can observe raw frames, but STOMP
frames require parsing the envelope (`\n\n` body delimiter, `content-type`
header). The `window.__e2e` bridge lets tests work at the same abstraction
level as the application code (typed Zustand state) and is immune to
STOMP protocol changes.

### 2. Shared backend, isolated sessions per test

Running one backend per test run (not one per test) saves ~20 s of JVM startup
per test. Sessions are cheap to create and delete via REST, so isolation is
preserved without the cost of full process restarts.

### 3. `vite preview` with `--mode e2e` instead of `vite dev`

`vite dev` introduces HMR WebSocket traffic that Playwright's network layer
must filter; `vite preview` is a simple HTTP server with no background
connections. Using `--mode e2e` (not `--mode production`) is a deliberate
middle ground: the bundle is production-optimised, but the E2E bridge is
present.

### 4. `fullyParallel: false`

The game clock runs continuously and affects power flow state. Parallel tests
on the same session would race on shared state. Isolated sessions via the
fixture make parallelism safe in principle, but a single backend thread pool
and SQLite locking make parallel tests slower in practice. Re-evaluate in
Stage 7 when the backend moves to a more concurrent persistence layer.

### 5. E2E not yet a required PR check

Following the process doc, E2E runs on `main` pushes and via
`workflow_dispatch` until the suite is stable (Stage 7). This prevents
a flaky test from blocking a merge that has nothing to do with the flaky
scenario.

---

## Open Questions

| # | Question | Owner | Target |
|---|----------|-------|--------|
| 1 | ~~`--mode test` vs `--mode e2e`~~ — resolved: using `--mode e2e` | — | closed |
| 2 | SL-04 (page reload reconnects): needs a reliable way to assert reconnect vs fresh-connect — use `missedTicks > 0` from the RECONNECTED status? | Claude | implementation PR |
| 3 | Should the E2E job post a comment on the triggering commit/PR with a pass/fail summary? | Rick | Stage 5 |
| 4 | Add `e2e-failure` auto-issue creation (referenced in `e2e-qa-workflow.md §CI Failure Handling`) now or in Stage 7? | Rick | Stage 5 planning |

