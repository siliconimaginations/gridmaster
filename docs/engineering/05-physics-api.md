# Physics REST API

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/05-physics-api`
**Depends on**: [01-network-model.md](01-network-model.md), [02-power-flow.md](02-power-flow.md), [03-contingency-analysis.md](03-contingency-analysis.md), [04-dispatch.md](04-dispatch.md)

---

## Purpose

This module defines the HTTP REST API that the game engine (Module 07) and
WebSocket layer (Module 10) use to invoke physics operations and retrieve
network state. It is the boundary between the physics engine packages and
all other server-side modules. No physics logic lives here — controllers
delegate immediately to the service interfaces defined in Modules 01–04.

---

## Scope

**In scope**
- Spring MVC controllers for network state, mutations, power flow, contingency
  analysis, dispatch, and unit commitment
- Request/response DTOs (separate from domain objects — no PowSyBl types leak)
- Input validation and error response format
- Session-scoped access (all endpoints are scoped to a `sessionId`)

**Out of scope**
- WebSocket endpoints (Module 10)
- Authentication/authorisation (Module 06 — session token passed as header)
- Frontend-facing UX concerns

---

## Endpoints

All paths are prefixed `/api/sessions/{sessionId}`.

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/network` | Current `GridNetwork` snapshot |
| `POST` | `/network/mutations` | Apply one or more `NetworkMutation`s |
| `GET` | `/powerflow` | Latest `PowerFlowResult` (cached) |
| `POST` | `/powerflow/run` | Force a synchronous power flow solve |
| `GET` | `/contingencies` | Latest `ContingencyAnalysisResult` (cached) |
| `POST` | `/contingencies/trigger` | Trigger async N-1 analysis |
| `POST` | `/dispatch` | Run economic dispatch, return `DispatchResult` |
| `POST` | `/unitcommitment` | Run unit commitment, return `UcResult` |
| `GET` | `/violations` | Current violation list (from latest power flow) |

---

## Domain Model

### Request / Response DTOs

DTOs are Kotlin data classes in the `api.dto` package. They are Jackson-serialised
and annotated with Bean Validation (`@NotNull`, `@Min`, etc.).

```kotlin
// POST /network/mutations
data class ApplyMutationsRequest(
    @field:NotEmpty val mutations: List<NetworkMutationDto>,
)

data class NetworkMutationDto(
    val type: String,       // "SET_GENERATOR_OUTPUT" | "TRIP_LINE" | etc.
    val targetId: String,
    val parameters: Map<String, Any> = emptyMap(),
)

// POST /dispatch
data class DispatchRequest(
    val totalLoadMw: Double,
    val mode: String = "MERIT_ORDER",       // "MERIT_ORDER" | "LP"
    val reserveMarginFraction: Double = 0.20,
    val securityConstrained: Boolean = false,
)

// POST /unitcommitment
data class UnitCommitmentRequest(
    @field:Size(min = 24, max = 24)
    val hourlyForecastMw: List<Double>,
    val reserveMarginFraction: Double = 0.20,
)
```

### Error response

All errors follow a consistent envelope:

```json
{
  "status": 400,
  "error": "INVALID_MUTATION",
  "message": "Generator G1 target 650 MW exceeds maxActivePowerMw 600 MW",
  "sessionId": "abc123",
  "timestamp": "2026-06-01T10:00:00Z"
}
```

---

## Design Decisions & Rationale

1. **Thin controllers — no logic.**
   Controllers validate input, map DTOs to domain types, delegate to services,
   and map results back to DTOs. No business or physics logic.

2. **Separate DTOs from domain objects.**
   `GridNetwork`, `PowerFlowResult`, etc. are domain objects owned by the
   physics layer. Exposing them directly as API responses would couple the
   API contract to internal structure. DTOs allow the API shape to evolve
   independently.

3. **`POST /powerflow/run` for synchronous solve.**
   The game engine normally relies on the cached result. Synchronous solve
   is exposed for tutorial missions that need an immediate updated state
   (e.g. "adjust dispatch then see the result").

4. **Session-scoped paths.**
   Every endpoint is scoped to a `sessionId`. This is the foundation for
   future multi-session support and makes the API self-documenting.

---

## Error Handling

| Scenario | HTTP status | Error code |
|----------|-------------|------------|
| Session not found | 404 | `SESSION_NOT_FOUND` |
| Invalid mutation (validation) | 400 | `INVALID_MUTATION` |
| Power flow `NETWORK_FAILURE` | 200 | status field in body = `NETWORK_FAILURE` |
| Physics service exception | 500 | `PHYSICS_ERROR` |
| Malformed JSON | 400 | `BAD_REQUEST` |

Power flow divergence is not a 500 — it is a valid game state returned in the response body.

---

## Testing Strategy

**Unit tests**: mock all service dependencies; assert correct HTTP status codes,
DTO mapping, and validation rejection for invalid inputs.

**Integration tests** (`@Tag("integration")`): Spring Boot test slice
(`@WebMvcTest`) with a real IEEE 14-bus session; assert end-to-end
`POST /mutations` → `POST /powerflow/run` → `GET /network` produces
updated voltages.

---

## Open Questions

None.
