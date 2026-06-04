# Session Model

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/06-session-model`
**Depends on**: [01-network-model.md](01-network-model.md)

---

## Purpose

This module manages the lifecycle of a game session: creation, persistence,
resumption, and deletion. A session ties together a player identity, a game
mode, the live IIDM network state, and the game clock position. Sessions
persist server-side in SQLite so the player can close and reopen the game
without losing progress.

---

## Scope

**In scope**
- `GameSession` entity and `SessionRepository` (SQLite via JPA)
- Session lifecycle: `create`, `save`, `load`, `delete`
- Single-user JWT authentication (hardcoded secret, no registration flow)
- Session token validation on every request

**Out of scope**
- Multi-user or multiplayer sessions (future)
- Role-based access control
- Session sharing or export

---

## Domain Model

```kotlin
@Entity
@Table(name = "game_sessions")
data class GameSession(
    @Id val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val mode: GameMode,
    val displayName: String,

    @Column(columnDefinition = "TEXT")
    val networkXml: String,           // PowSyBl IIDM serialised via NetworkSerDe

    val gameTimeEpochMinutes: Long,   // accumulated game-time minutes
    val clockSpeedMultiplier: Int = 1,
    val clockState: ClockState = ClockState.PAUSED,

    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val completedAt: Instant? = null,
)

enum class GameMode { TUTORIAL, FREE_PLAY, CHALLENGE }
enum class ClockState { RUNNING, PAUSED, SLOW, STOPPED }

interface SessionRepository : JpaRepository<GameSession, String> {
    fun findByUserId(userId: String): List<GameSession>
    fun findByUserIdAndMode(userId: String, mode: GameMode): List<GameSession>
}
```

### JWT Authentication

Single user; no registration. A JWT is issued on first launch and stored by
the client. Token is validated on every request via a Spring Security filter.

```kotlin
data class AuthToken(
    val userId: String,           // stable UUID generated on first launch
    val issuedAt: Instant,
    val expiresAt: Instant,       // rolling 30-day expiry, refreshed on use
)
```

JWT secret is set in `application.yml` under `gridmaster.auth.jwt-secret`.
For local dev, a default insecure secret is provided; production deployment
must override.

---

## Session Lifecycle

```
Client first launch
  → POST /api/auth/token  (no credentials; server issues JWT)
  → Client stores JWT

Create session
  → POST /api/sessions  { mode, displayName, networkPreset }
  → Server loads seed IIDM (IEEE 14-bus for tutorial, 50-bus for free play)
  → Persists GameSession → returns sessionId + JWT

Resume session
  → GET /api/sessions          (list user's sessions)
  → GET /api/sessions/{id}     (load session; deserialise IIDM)

Auto-save
  → Every N ticks (configurable; default 10) game engine calls SessionRepository.save()
  → Also saved on pause and on clean shutdown

Delete session
  → DELETE /api/sessions/{id}
```

---

## Design Decisions & Rationale

1. **IIDM XML stored as TEXT in SQLite.**
   PowSyBl's `NetworkSerDe` produces a self-contained XML that round-trips
   perfectly. SQLite TEXT handles up to ~1 GB; a 500-bus network serialises
   to ~500 KB — well within limits.

2. **`gameTimeEpochMinutes` as a single counter.**
   Game time is tracked as accumulated minutes from epoch (game start).
   The clock module (07) increments this each tick. Storing it in the session
   allows precise resumption.

3. **JWT with no registration for single-user dev scope.**
   The game is currently single-user and local. Full auth is unnecessary
   overhead. A rolling JWT gives basic protection against accidental
   cross-session access without a login screen.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Session not found | 404 `SESSION_NOT_FOUND` |
| IIDM deserialisation fails on load | 500; session marked corrupted; player prompted to start new session |
| SQLite write failure | Log + retry once; if still failing, pause clock and surface alert |
| Expired JWT | 401; client re-issues via `POST /api/auth/token` |

---

## Testing Strategy

**Unit tests**: `SessionRepository` save/load round-trip with in-memory H2.
**Integration tests**: full Spring Boot test; create → save → reload →
assert `networkXml` round-trips through `NetworkSerDe` without loss;
assert JWT validation rejects tampered tokens.

---

## Open Questions

1. **Network presets**: `POST /api/sessions` accepts a `networkPreset`
   string (e.g. `"ieee14"`, `"freeplay50"`). The preset loader maps this
   to a bundled XIIDM file in `resources/networks/`. Preset list TBD —
   confirm approach before implementation.
