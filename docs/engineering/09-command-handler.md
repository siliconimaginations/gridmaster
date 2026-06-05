# Command Handler

**Stage**: 1
**Status**: Draft — v2, addressing review comments
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
     * Validate and apply a single player command. Runs power flow after mutation.
     * Returns a CommandResult with one CommandOutcome entry.
     * Synchronous — blocks until power flow completes.
     */
    fun handle(command: PlayerCommand, sessionId: String): CommandResult

    /**
     * Validate and apply a batch of player commands atomically.
     * All commands are validated first; if any fail, the entire batch is rejected
     * and zero mutations are applied.
     * All mutations are applied in order, then a single power flow is run.
     * Returns a CommandResult with one CommandOutcome entry per command.
     */
    fun handleBatch(commands: List<PlayerCommand>, sessionId: String): CommandResult

    /**
     * Apply a list of NetworkMutations directly (from event engine or
     * dispatch service). Skips player-level validation.
     * Returns a CommandResult with a single synthetic CommandOutcome.
     */
    fun applyMutations(mutations: List<NetworkMutation>, sessionId: String): CommandResult
}

// Unified result for both single and batch commands.
// Single command: commandOutcomes has one entry.
// Batch command: commandOutcomes has one entry per command in the batch.
data class CommandResult(
    val success: Boolean,                        // false if any command was rejected
    val snapshot: GridNetwork,
    val powerFlowResult: PowerFlowResult,
    val newAlerts: List<Alert>,
    val commandOutcomes: List<CommandOutcome>,
)

data class CommandOutcome(
    val commandType: String,
    val success: Boolean,
    val rejectionReason: String? = null,         // non-null if this command was rejected
)

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
// See CommandResult and CommandOutcome defined above (unified type).
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

### Single command
```
PlayerCommand received
        │
        ▼
1. Validate command (domain rules above)
   → if invalid: return CommandResult(success=false) with CommandOutcome.rejectionReason set
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

### Batch command (handleBatch)
```
List<PlayerCommand> received
        │
        ▼
1. Validate ALL commands — collect rejections
   → if any invalid: return CommandResult(success=false) — no mutations applied
        │
        ▼
2. Translate each command to NetworkMutation(s)
        │
        ▼
3. Apply all mutations in order via IidmNetworkMapper
        │
        ▼
4. PowerFlowService.solve() — ONE solve for the entire batch
        │
        ▼
5. Violation scan → generate Alerts
        │
        ▼
6. ContingencyAnalysisService.triggerAsync() if any topology change
        │
        ▼
7. Return CommandResult with per-command outcomes + shared snapshot
```

**Key benefit of batching**: N commands → 1 power flow solve instead of N.
This is critical for applying dispatch results (one target per generator) or
UC commitment actions (commit/decommit multiple units simultaneously) without
running an intermediate power flow after each generator change.

**Use cases for handleBatch**:
- Applying `DispatchResult.generatorTargets` (one `SetGeneratorOutput` per generator)
- Applying `UcResult.commitmentActions` (multiple `CommitGenerator`/`DecommitGenerator`)
- Event engine applying multiple simultaneous `EventEffect`s
- Tutorial missions setting up a specific multi-element initial state
- Player using a planned "batch edit" mode in the UI

---

## Design Decisions & Rationale

1. **Single entry point for all mutations; batching for efficiency.**
   All state changes — player commands, event effects, dispatch results —
   funnel through `CommandHandler`. `handleBatch` applies N mutations then
   runs a single power flow, avoiding N intermediate solves for operations
   like applying a full dispatch result or UC schedule. All-or-nothing
   validation ensures no partial mutations on rejection.

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
| Single command validation failure | `CommandResult(success=false)` — `commandOutcomes[0].rejectionReason` explains why; no mutation applied |
| Batch validation failure (any command) | `CommandResult(success=false)` — zero mutations applied; `commandOutcomes` populated with per-command pass/fail |
| `applyMutation` throws `InvalidMutationException` | Wrapped as failed `CommandResult` |
| Power flow returns `NETWORK_FAILURE` | `CommandResult(success=true)` — mutation applied but grid failed; snapshot and alerts reflect the failure state |
| Unexpected exception | Log; return `CommandResult(success=false)` with `commandOutcomes[0].rejectionReason="Internal error"` |

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

## Resolved Design Points (from review)

1. **Batch command support**: `handleBatch(List<PlayerCommand>)` added. Validates all commands first (all-or-nothing), applies all mutations in order, runs a single power flow. `CommandResult` is the unified return type for both — single commands return a one-element `commandOutcomes` list. No separate batch result type needed. Use cases: dispatch results, UC schedules, event effects, tutorial state setup.
