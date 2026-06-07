# HUD — Top Pill Badges + Bottom Clock Controls

**Stage**: 4  
**Status**: Draft  
**Implements**: GitHub issues #83 (Top HUD + Bottom HUD)  
**Depends on**:
- [11-frontend-scene.md](11-frontend-scene.md) — canvas container must be a sibling, not the root element
- [12-frontend-state-api.md](12-frontend-state-api.md) — Zustand store provides all game data; `sendCommandOptimistic` dispatches clock commands

---

## Purpose

Provides always-visible game feedback and clock control as floating React overlays on top of the Babylon.js canvas. The top HUD tells the player what their grid is doing; the bottom HUD lets them control simulated time and trigger key actions.

---

## Scope

**In scope (issue #83)**
- `TopHud` component: four pill badges — clock, total load, system price, grid health
- `BottomHud` component: clock controls (play/pause, speed selector) + contextual action buttons
- CSS overlay architecture wiring: App.tsx `<GameRoot>` wrapper that stacks canvas and HUD layers
- Unit tests for all new components

**Out of scope**
- Alert toast stack — issue #85
- Day-ahead timeline strip — issue #84
- Component inspector popup — issue #86
- Event card panel, dispatch panel, planning panel — issues #87–#89
- Multiplayer or per-player HUD variants — future

---

## Key Concepts / Domain Model

### Overlay architecture

`App.tsx` currently renders a bare `<canvas>`. To add HUD overlays, it must be
refactored into a container div that stacks the canvas and the HUD layers using
CSS absolute positioning:

```
<div id="game-root">              ← position: relative; width/height: 100%
  <canvas />                      ← position: absolute; inset: 0
  <div id="hud-root">             ← position: absolute; inset: 0; pointer-events: none
    <TopHud />                    ← position: absolute; top: 0
    <BottomHud />                 ← position: absolute; bottom: 0
  </div>
</div>
```

`pointer-events: none` on `#hud-root` passes mouse events through to the canvas
by default. Interactive elements (buttons, clickable pills) set
`pointer-events: auto` on themselves.

### Data flow

All HUD data is read-only from the Zustand store. No local state is needed
except for transient UI state (e.g. button hover). HUD components are pure
presentational wrappers over store slices.

```
Zustand store
  ├── tickNumber, gameTimeMinutes  → TopHud clock pill, BottomHud day label
  ├── network.loads[*].activePowerMw  → TopHud load pill (sum)
  ├── clockState, clockSpeedMultiplier → BottomHud play/pause, active speed button
  ├── violations                  → TopHud health pill colour
  └── pendingEventCards           → BottomHud contextual buttons
```

### System marginal price

`GameStateUpdate` does not currently carry a price field — the store has no
`systemMarginalPrice` slice. For the MVP, the price pill displays `—` until the
field is added to the backend protocol. A `// TODO: #<issue>` stub is added
in the component.

---

## API / Interface

### Store selectors used by HUD

```ts
// TopHud reads
const { gameTimeMinutes, network, violations, pendingEventCards } =
  useGameStore(state => ({
    gameTimeMinutes: state.gameTimeMinutes,
    network: state.network,
    violations: state.violations,
    pendingEventCards: state.pendingEventCards,
  }))

// BottomHud reads + actions
const { clockState, clockSpeedMultiplier, sessionId, sendCommandOptimistic } =
  useGameStore(state => ({
    clockState: state.clockState,
    clockSpeedMultiplier: state.clockSpeedMultiplier,
    sessionId: state.sessionId,
    sendCommandOptimistic: state.sendCommandOptimistic,
  }))
```

### Commands dispatched by BottomHud

All commands are sent via `sendCommandOptimistic`. The clock state is a simple
deterministic toggle, so all three commands carry an optimistic update function.

```ts
// Pause
sendCommandOptimistic(
  { commandType: 'PauseClock', payload: {} },
  // No network change — clock state update arrives via next GameStateUpdate
)

// Resume
sendCommandOptimistic({ commandType: 'ResumeClock', payload: {} })

// Speed change
sendCommandOptimistic(
  { commandType: 'SetClockSpeed', payload: { multiplier } },
)
```

### Component props

```ts
// Both components are connected to the Zustand store directly — no props required.
// They are leaf components: parents pass nothing; all data comes from the store.

/** Top horizontal pill bar. */
export function TopHud(): JSX.Element

/** Bottom HUD: clock controls (left) + contextual actions (right). */
export function BottomHud(): JSX.Element
```

### Derived display values

```ts
/** Converts game-time minutes to "Day N · HH:MM" string. */
function formatGameTime(gameTimeMinutes: number): string

/** Sums active power across all loads. Returns "— MW" when network is null. */
function totalLoadMw(network: GridNetworkDto | null): string

/** Maps violation count to health label + CSS class. */
function gridHealthStatus(violations: ViolationDto[]): {
  label: 'Grid healthy' | 'N-1 risks' | 'Failure'
  severity: 'ok' | 'warning' | 'critical'
}
```

---

## File Layout

```
src/ui/
  TopHud.tsx           — four pill badges
  TopHud.module.css    — pill styles
  BottomHud.tsx        — clock controls + action buttons
  BottomHud.module.css — control bar styles
  hud.ts               — shared derive helpers (formatGameTime, totalLoadMw, gridHealthStatus)
  __tests__/
    TopHud.test.tsx
    BottomHud.test.tsx
    hud.test.ts
```

`App.tsx` is updated to introduce the `<GameRoot>` container and render `<TopHud>` and `<BottomHud>`.

---

## Design Decisions & Rationale

1. **CSS Modules over inline styles or a CSS framework.** Vite supports CSS
   Modules out of the box (`*.module.css`). They give locally-scoped class names
   without adding a runtime library (no Tailwind compiler needed, no emotion
   bundle). Inline styles were rejected because pseudo-classes (`:hover`,
   `:focus`) and media queries are not expressible inline.

2. **`pointer-events: none` on the HUD root.** The Babylon.js canvas handles
   pointer events for scene picking (clicking grid elements). Blocking events
   on the entire HUD layer would break scene interaction. Individual HUD
   interactive elements opt back in with `pointer-events: auto`.

3. **No local UI state for clock commands.** The speed buttons and play/pause
   button read `clockState` / `clockSpeedMultiplier` directly from the Zustand
   store. When a command is sent, the store state updates either via optimistic
   path or the next `GameStateUpdate` tick — the button reflects reality without
   needing a local `isLoading` flag.

4. **System marginal price deferred.** The backend `GameStateUpdate` message does
   not yet include a price field (not in the WebSocket protocol spec). The price
   pill renders `—` with a `// TODO` comment rather than computing a
   client-side approximation. Adding the field to the protocol is a future
   backend task.

5. **Speed steps: 1×, 10×, 60×, 100×** (not 1×/5×/10×/60×/100×).  
   The UX doc shows both variants in different sections. The four-step version
   (1×, 10×, 60×, 100×) is used — it maps cleanly to the tick timings in
   `docs/ux/04-time-axis.md` and keeps the button group compact.

6. **Contextual actions: max 4 visible.** Per the UX spec. Overflow button ("…")
   is rendered in DOM but hidden (`display: none`) until a 5th action is
   present. This keeps the MVP simple and avoids needing a popover component
   now.

---

## Error Handling

- **No session active** (`sessionId === null`): clock control buttons are
  `disabled`; tooltips read "No active session". Action buttons are hidden.
- **Clock command rejected** (`CommandAck.success === false`): the store rolls
  back optimistically applied state (see §12). The HUD re-renders automatically
  from the corrected store state. No additional error handling in HUD.
- **Network null**: load pill shows `— MW`, health pill shows `—`.

---

## Testing Strategy

**Unit tests (Vitest + React Testing Library)**

`hud.test.ts` — pure function tests, no React:
- `formatGameTime(0)` → `'Day 1 · 00:00'`
- `formatGameTime(1440)` → `'Day 2 · 00:00'`
- `totalLoadMw(null)` → `'— MW'`
- `totalLoadMw(networkWith2Loads)` → correct sum string
- `gridHealthStatus([])` → `{ label: 'Grid healthy', severity: 'ok' }`
- `gridHealthStatus(criticalViolations)` → `{ label: 'Failure', severity: 'critical' }`

`TopHud.test.tsx` — render tests with mocked Zustand store:
- Renders clock pill with formatted game time
- Renders load pill with summed MW
- Health pill has `severity-ok` class when no violations
- Health pill has `severity-critical` class when violations present

`BottomHud.test.tsx` — render + interaction tests:
- Play button renders ▶ when clock is PAUSED, ⏸ when RUNNING
- Active speed button has `active` class
- Clicking pause dispatches `PauseClock` command
- Clicking 60× dispatches `SetClockSpeed` with `multiplier: 60`
- Buttons are disabled when `sessionId` is null

**Integration tests**: none required at this stage — all data paths are
already covered by `useGameStore.test.ts` and `wsClient.test.ts`.

**E2E tests**: scenario HUD-01 from `docs/process/e2e-qa-workflow.md`
(top HUD shows non-zero values after first tick) — deferred to Stage 7
per the e2e workflow doc.

---

## Open Questions

| # | Question | Owner | Target |
|---|----------|-------|--------|
| 1 | Add `systemMarginalPrice` to `GameStateUpdate`? Requires backend protocol change and new store slice. | Rick | After Stage 4 |
| 2 | Should the health pill click open a panel now (#86 is a separate issue), or just be a non-interactive badge in #83? | Rick | Before implementing #83 |
