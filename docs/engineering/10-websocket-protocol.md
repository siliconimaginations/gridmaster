# WebSocket Protocol

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/10-websocket-protocol`
**Depends on**: [06-session-model.md](06-session-model.md), [07-game-clock.md](07-game-clock.md), [01-network-model.md](01-network-model.md)

---

## Purpose

This module defines the WebSocket protocol between the Spring Boot server
and the Babylon.js/React frontend. The server pushes `GameStateUpdate`
messages to the client each tick; the client sends `PlayerCommand` messages
to the server. The protocol is the real-time communication backbone of the
game.

---

## Scope

**In scope**
- STOMP over WebSocket (Spring WebSocket with SockJS fallback)
- `GameStateUpdate`: server → client, published each tick
- `PlayerCommandMessage`: client → server
- Connection lifecycle: handshake, session binding, reconnection
- Delta encoding: only changed fields sent per tick for bandwidth efficiency

**Out of scope**
- HTTP REST endpoints (Module 05)
- Frontend rendering of state (Module 11+)
- Multiplayer / multi-client sessions (future)

---

## Transport

Spring's STOMP-over-WebSocket with SockJS fallback for environments that
block raw WebSocket. SockJS transparently falls back to long-polling.

```
Client connects:  ws://localhost:8080/ws  (or SockJS endpoint)
STOMP subscribe:  /topic/session/{sessionId}/state   ← game state updates
STOMP send:       /app/session/{sessionId}/command   → player commands
```

Authentication: the JWT from Module 06 is passed as a STOMP connect header
(`Authorization: Bearer <token>`). The server validates it in the
`ChannelInterceptor` before allowing any subscription.

---

## Message Types

### Server → Client: `GameStateUpdate`

Published to `/topic/session/{sessionId}/state` after every tick and after
every command that changes network state.

```typescript
// TypeScript interface (mirrors Kotlin data class)
interface GameStateUpdate {
  type: "FULL" | "DELTA";
  sessionId: string;
  tickNumber: number;
  gameTimeMinutes: number;
  clockState: "RUNNING" | "PAUSED" | "SLOW" | "STOPPED";
  clockSpeedMultiplier: number;

  // Present on FULL; partial on DELTA (only changed fields)
  network?: GridNetworkDto;
  powerFlowStatus?: "CONVERGED" | "PARTIAL" | "NETWORK_FAILURE" | "FAILED";
  violations?: ViolationDto[];
  alerts?: AlertDto[];
  pendingEventCards?: EventCardDto[];
}
```

**FULL** messages are sent on connection/reconnection and every
`fullStateIntervalTicks` ticks (default: 30). **DELTA** messages contain
only fields that changed since the previous tick, keeping bandwidth low
at fast speeds.

### Client → Server: `PlayerCommandMessage`

Sent to `/app/session/{sessionId}/command`.

```typescript
interface PlayerCommandMessage {
  commandType: string;          // matches PlayerCommand subclass name
  payload: Record<string, unknown>;
}
```

Examples:
```json
{ "commandType": "SetGeneratorOutput", "payload": { "generatorId": "G1", "targetMw": 200 } }
{ "commandType": "TripElement",        "payload": { "elementId": "L5", "elementType": "LINE" } }
{ "commandType": "PauseClock",         "payload": {} }
```

The server deserialises `payload` into the appropriate `PlayerCommand`
subclass and routes to the `CommandHandler`.

### Server → Client: `CommandAck`

Sent directly to the requesting client after a command is processed
(via `/queue/session/{sessionId}/ack` — user-specific destination).

```typescript
interface CommandAck {
  commandType: string;
  success: boolean;
  rejectionReason?: string;
  appliedAt: number;  // tickNumber when applied
}
```

### Server → Client: `ConnectionStatus`

Sent on connect/disconnect events.

```typescript
interface ConnectionStatus {
  type: "CONNECTED" | "RECONNECTED" | "SESSION_NOT_FOUND" | "AUTH_FAILED";
  sessionId?: string;
  missedTicks?: number;  // on RECONNECTED — how many ticks client missed
}
```

---

## Delta Encoding

To keep bandwidth low at 10×–100× speeds, the server tracks the last
`GridNetworkDto` hash sent to each client and sends only changed top-level
fields. Field-level delta rules:

| Field | Delta condition |
|-------|----------------|
| `network.buses` | Sent if any bus voltage changed by > 0.001 pu |
| `network.lines` | Sent if any line current changed by > 1 A |
| `violations` | Sent if violation list changed (add/remove/severity change) |
| `alerts` | New alerts only (append-only on client) |
| `clockState` | Sent on every change |
| `pendingEventCards` | Sent when a new card arrives or is resolved |

A `FULL` message is always sent after reconnection to resync client state.

---

## Connection Lifecycle

```
Client connects + sends STOMP CONNECT with JWT
        │
        ▼
Server validates JWT → binds sessionId
        │
        ▼
Server sends ConnectionStatus(CONNECTED) + immediate FULL GameStateUpdate
        │
Client subscribes to /topic/session/{id}/state
        │
── normal operation ──
Server publishes DELTA GameStateUpdate each tick
Client sends PlayerCommandMessage → Server → CommandAck
        │
── disconnection ──
Client reconnects (SockJS auto-retry with backoff)
Server sends ConnectionStatus(RECONNECTED, missedTicks=N)
Server sends FULL GameStateUpdate to resync
```

---

## Design Decisions & Rationale

1. **STOMP over WebSocket rather than raw WebSocket.**
   STOMP provides pub/sub semantics, message acknowledgement, and per-user
   destinations out of the box with Spring's `@MessageMapping`. Raw WebSocket
   would require reimplementing these. SockJS fallback handles restrictive
   proxies.

2. **FULL + DELTA dual mode.**
   Pure delta would require complex client-side state reconstruction; pure
   full would waste bandwidth at high speeds. The hybrid (FULL every 30 ticks
   + DELTA otherwise) bounds both complexity and bandwidth.

3. **`CommandAck` on user-specific destination.**
   A command response must go back to the commanding client, not broadcast
   to all subscribers. STOMP user destinations (`/queue/...`) handle this
   natively without any application-level routing.

4. **JWT in STOMP connect header, not URL.**
   Tokens in URLs appear in server logs and browser history. STOMP headers
   are not logged by default.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| JWT invalid at STOMP CONNECT | Server sends `ConnectionStatus(AUTH_FAILED)`; closes connection |
| Session not found | `ConnectionStatus(SESSION_NOT_FOUND)`; closes connection |
| Client disconnects mid-game | Clock continues (server-authoritative); client reconnects and receives FULL update |
| Server-side broadcast exception | Log; skip that tick's broadcast; next tick sends FULL to recover |

---

## Testing Strategy

**Unit tests**: mock `SimpMessagingTemplate`; assert correct destinations and
payload structure for FULL vs DELTA messages; assert delta logic sends only
changed fields.

**Integration tests**: Spring Boot test with embedded WebSocket; connect a
test client; send `SetGeneratorOutput` command; assert `CommandAck(success=true)`
received; assert next `GameStateUpdate` contains updated generator output.

---

## Open Questions

1. **`fullStateIntervalTicks` tuning**: 30 ticks at 1× = 30 seconds between
   full resyncs. At 100× this is 0.3 seconds. Should the interval be in
   real-time seconds rather than ticks so that high-speed play doesn't
   flood clients with full messages?
