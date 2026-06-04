# Command Handler

**Stage**: 1
**Status**: Draft — awaiting review
**Branch**: `stage/1/09-command-handler`
**Depends on**: [01-network-model.md](01-network-model.md), [02-power-flow.md](02-power-flow.md), [03-contingency-analysis.md](03-contingency-analysis.md), [04-dispatch.md](04-dispatch.md)

---

## Purpose

The command handler is the single entry point for all state-changing
operations on the live network — whether initiated by the player (via REST
or WebSocket), the dispatch service, or the event engine. It validates
the operation, translates it into one or more `NetworkMutation`s, applies
them to the IIDM network, and triggers the downstream physics pipeline.

---

## Scope

**In scope**
- `CommandHandler`: validate → mutate → pipeline
- `PlayerCommand` sealed hierarchy (all player-initiated actions)
- `CommandResult`: outcome + updated `GridNetwork` snapshot
- Validation rules per command type
- Conversion of `EventEffect`s (Module 08) to `NetworkMutation`s

**Out of scope**
- HTTP/WebSocket transport (Modules 05, 10)
- Physics solvers (Modules 02–04)
- Event scheduling (Module 08)

---

## Domain Model

```kotlin
interface CommandHandler {
    /**
     * Validate and apply a player command. Runs power flow after mutation.
     * Synchronous — blocks until power flow completes.
     */
    fun handle(command: PlayerCommand, sessionId: String): CommandResult

    /**
     * Apply a list of NetworkMutations directly (from event engine or
     * dispatch service). Skips player-level validation.
     */
    fun applyMutations(mutations: List<NetworkMutation>, sessionId: String): CommandResult
}

// ── Player commands ──────────────────────────────────────────────────────────

sealed class PlayerCommand {
    abstract val sessionId: String

    // Real-time operations
    data class SetGeneratorOutput(
        override val sessionId: String,
        val generatorId: String,
        val targetMw: Double,
    ) : PlayerCommand()

    data class SetGeneratorVoltage(
        override val sessionId: String,
        val generatorId: String,
        val targetVoltagePu: Double,
    ) : PlayerCommand()

    data class TripElement(
        override val sessionId: String,
        val elementId: String,
        val elementType: EquipmentType,
    ) : PlayerCommand()

    data class ConnectElement(
        override val sessionId: String,
        val elementId: String,
        val elementType: EquipmentType,
    ) : PlayerCommand()

    data class SetTapPosition(
        override val sessionId: String,
        val transformerId: String,
        val tapPosition: Int,
    ) : PlayerCommand()

    data class ShedLoad(
        override val sessionId: String,
        val loadId: String,
        val fractionToShed: Double,     // 0.0–1.0
    ) : PlayerCommand()

    // Dispatch operations
    data class RunEconomicDispatch(
        override val sessionId: String,
        val totalLoadMw: Double,
        val mode: DispatchMode = DispatchMode.MERIT_ORDER,
        val securityConstrained: Boolean = false,
    ) : PlayerCommand()

    data class CommitGenerator(
        override val sessionId: String,
        val generatorId: String,
    ) : PlayerCommand()

    data class DecommitGenerator(
        override val sessionId: String,
        val generatorId: String,
    ) : PlayerCommand()

    data class ApplyUcSchedule(
        override val sessionId: String,
        val schedule: List<GeneratorSchedule>,
    ) : PlayerCommand()

    // Clock control
    data class SetClockSpeed(
        override val sessionId: String,
        val multiplier: Int,            // 1–100
    ) : PlayerCommand()

    data class PauseClock(override val sessionId: String) : PlayerCommand()
    data class ResumeClock(override val sessionId: String) : PlayerCommand()

    // Event card response
    data class RespondToEventCard(
        override val sessionId: String,
        val eventId: String,
        val optionIndex: Int,
    ) : PlayerCommand()
}

// ── Result ───────────────────────────────────────────────────────────────────

data class CommandResult(
    val success: Boolean,
    val snapshot: GridNetwork,          // updated snapshot post-power-flow
    val powerFlowResult: PowerFlowResult,
    val newAlerts: List<Alert>,
    val rejectionReason: String? = null, // non-null if success=false
)
```

### Validation rules (examples)

| Command | Validation |
|---------|------------|
| `SetGeneratorOutput` | `targetMw` in `[minActivePowerMw, maxActivePowerMw]`; generator must be committed |
| `SetGeneratorVoltage` | `targetVoltagePu` in `[0.9, 1.1]`; generator must have voltage regulation |
| `TripElement` | Element must be currently connected |
| `ConnectElement` | Element must be currently disconnected; connecting must not create an island |
| `SetTapPosition` | `tapPosition` within transformer's tap range |
| `ShedLoad` | `fractionToShed` in `[0.0, 1.0]`; load must be connected |
| `SetClockSpeed` | `multiplier` in `[1, 100]` |
| `DecommitGenerator` | Generator must not be the only source in an island |

---

## Command Pipeline

```
PlayerCommand received
        │
        ▼
1. Validate command (domain rules above)
   → if invalid: return CommandResult(success=false, rejectionReason=...)
        │
        ▼
2. Translate to NetworkMutation(s)
        │
        ▼
3. IidmNetworkMapper.applyMutation(network, mutation)
        │
        ▼
4. PowerFlowService.solve(network, parameters)
        │
        ▼
5. Violation scan → generate Alerts
        │
        ▼
6. ContingencyAnalysisService.triggerAsync() if topology changed
        │
        ▼
7. Return CommandResult with updated snapshot
```

---

## Design Decisions & Rationale

1. **Single entry point for all mutations.**
   All state changes — player commands, event effects, dispatch results —
   funnel through `CommandHandler`. This ensures power flow always runs
   after every mutation and the game state is never left in an un-solved state.

2. **Synchronous command handling.**
   `handle()` blocks until the power flow completes and returns the updated
   snapshot. This makes the REST API simple (request → updated state in
   response) and avoids race conditions between mutations and reads.

3. **Separate `applyMutations` for internal callers.**
   The event engine and dispatch service produce `NetworkMutation`s directly
   (already validated at their layer). Routing them through `applyMutations`
   skips player-level validation but still runs the physics pipeline.

4. **`ConnectElement` island check.**
   Connecting a previously isolated element could split the network if the
   element was carrying network topology implications. The validation checks
   for this explicitly using PowSyBl's topology analysis before applying.

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Validation failure | `CommandResult(success=false, rejectionReason=...)` — no mutation applied |
| `applyMutation` throws `InvalidMutationException` | Wrapped as failed `CommandResult` |
| Power flow returns `NETWORK_FAILURE` | `CommandResult(success=true)` — mutation applied but grid failed; snapshot and alerts reflect the failure state |
| Unexpected exception | Log; return `CommandResult(success=false, rejectionReason="Internal error")` |

---

## Testing Strategy

**Unit tests**: mock physics services; assert each command type produces correct
mutations; assert validation rules reject out-of-range inputs; assert island
check fires for topology-changing connects.

**Integration tests**: full pipeline on IEEE 14-bus; `SetGeneratorOutput` →
assert power flow runs → assert snapshot updated; `TripElement` on a line →
assert contingency analysis triggered; `RunEconomicDispatch` → assert
`DispatchService` called and results applied as mutations.

---

## Open Questions

None.
