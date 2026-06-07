# Frontend State & API Layer

## Purpose

Defines the Zustand game-state store, the WebSocket STOMP client that keeps
it in sync with the server, and the REST client used for authentication and
session management. Together these form the data backbone of the frontend:
the scene and UI layers read from the store and dispatch commands through it.

---

## Scope

**In scope (issue #81)**
- Zustand store shape and slice design
- `GameStateUpdate` ingestion — FULL and DELTA handling
- WebSocket STOMP client (`@stomp/stompjs` + SockJS fallback)
- Connection lifecycle: connect, subscribe, reconnect, disconnect
- `PlayerCommandMessage` dispatch
- `src/api/` and `src/state/` directory scaffold

**Out of scope**
- REST session and auth endpoints — issue #82
- React UI components that consume the store — issues #83–#89
- Scene rendering triggered by store updates — issue #79
- Multi-client / multiplayer — future

---

## Key Concepts / Domain Model

### Store slices

```
src/state/
  useGameStore.ts        — root Zustand store (all slices combined)
  slices/
    networkSlice.ts      — GridNetworkDto, violations, power-flow status
    clockSlice.ts        — tickNumber, gameTimeMinutes, clockState, speed
    alertSlice.ts        — AlertDto[], EventCardDto[]
    connectionSlice.ts   — ws status, sessionId, reconnect count
```

```ts
// Abbreviated shape
interface GameStore {
  // Network
  network: GridNetworkDto | null
  powerFlowStatus: PowerFlowStatus | null
  violations: ViolationDto[]

  // Clock
  tickNumber: number
  gameTimeMinutes: number
  clockState: ClockState
  clockSpeedMultiplier: number

  // Alerts
  alerts: AlertDto[]
  pendingEventCards: EventCardDto[]

  // Connection
  connectionStatus: 'disconnected' | 'connecting' | 'connected' | 'reconnecting'
  sessionId: string | null

  // Actions
  applyUpdate: (update: GameStateUpdate) => void
  sendCommand: (msg: PlayerCommandMessage) => void
  connect: (sessionId: string, jwtToken: string) => void
  disconnect: () => void
}
```

### FULL vs DELTA

`applyUpdate` checks `update.type`:
- **FULL**: replace all network/clock/alert fields unconditionally.
- **DELTA**: `Object.assign` only the fields present in the update (fields
  absent in a DELTA are `undefined` and must not overwrite existing state).

---

## API / Interface

### src/api/wsClient.ts

```ts
/**
 * Wraps @stomp/stompjs with SockJS fallback.
 * Owned by the Zustand store; callers interact via store actions only.
 */
export class WsClient {
  constructor(onMessage: (update: GameStateUpdate) => void, onStatus: (s: ConnectionStatus) => void)
  connect(sessionId: string, token: string): void
  send(sessionId: string, msg: PlayerCommandMessage): void
  disconnect(): void
}
```

### src/state/useGameStore.ts

```ts
export const useGameStore: UseBoundStore<StoreApi<GameStore>>
```

Components use selector hooks to avoid re-rendering on unrelated updates:
```ts
const clockState = useGameStore(s => s.clockState)
const violations = useGameStore(s => s.violations)
```

---

## Design Decisions & Rationale

### 1. Zustand over Redux or React Context

| Option | Pros | Cons | Decision |
|--------|------|------|----------|
| Redux Toolkit | Mature, devtools | Boilerplate, overkill for single-session game | Rejected |
| React Context | Built-in | Re-renders entire tree on any change | Rejected |
| **Zustand** | Minimal boilerplate, selector-based subscription, no Provider | Less structured | **Chosen** — already in dependencies |

### 2. `@stomp/stompjs` + SockJS (not native WebSocket)

The backend uses Spring's STOMP-over-WebSocket. `@stomp/stompjs` v6 handles
STOMP framing, heartbeat, and reconnection; SockJS handles environments that
block raw WebSocket. Using native WebSocket would require reimplementing STOMP
framing manually.

### 3. WsClient as a class owned by the store, not a React hook

The WebSocket connection must survive React re-renders and StrictMode
double-mounts. A store-owned class (analogous to `SceneManager`) avoids
the connection being torn down unexpectedly. The store's `connect()` action
lazily creates the `WsClient`; `disconnect()` disposes it.

### 4. DELTA handling via partial `Object.assign`

DELTA messages omit unchanged fields (they are absent, not `null`). Spreading
the DELTA over existing state with `{ ...state, ...defined(delta) }` where
`defined()` strips `undefined` keys gives correct partial-update semantics
without a deep-merge library.

### 5. All server-to-client types mirrored as TypeScript interfaces

Types live in `src/api/types.ts` and are the single source of truth for the
frontend. They mirror the Kotlin data classes in `10-websocket-protocol.md`.
No code-gen; types are kept in sync manually (flagged in PR if the protocol doc changes).

---

## Error Handling

| Failure | Handling |
|---------|----------|
| STOMP `AUTH_FAILED` | Set `connectionStatus = 'disconnected'`; dispatch alert |
| STOMP `SESSION_NOT_FOUND` | Same as AUTH_FAILED; navigate to lobby |
| Network drop | `@stomp/stompjs` auto-reconnects with exponential backoff; store shows `'reconnecting'` |
| Malformed message | `try/catch` in `onMessage`; log error; skip update |
| Command send while disconnected | Queue command; flush on reconnect (max queue: 10) |

---

## Testing Strategy

**Unit tests (Vitest)**
- `applyUpdate` with FULL message: assert all fields replaced
- `applyUpdate` with DELTA message: assert only present fields updated, rest unchanged
- `WsClient`: mock `@stomp/stompjs` `Client`; assert `connect` called with correct headers; assert `send` publishes to correct destination

**Integration tests**: deferred — require a live Spring backend; covered by the WebSocket module's existing integration tests on the server side.

---

## Open Questions

None.
