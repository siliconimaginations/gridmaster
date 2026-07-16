# Capital Budget Model (Free Play investment funding)

**Stage**: 6
**Status**: Draft — v1
**Branch**: `stage/6/18-budget-model`
**Depends on**: [17-grid-expansion.md](17-grid-expansion.md), [04-dispatch.md](04-dispatch.md), [08-event-engine.md](08-event-engine.md), [07-game-clock.md](07-game-clock.md)
**UX reference**: [docs/ux/07-planning-panel.md](../ux/07-planning-panel.md)

---

## Purpose

Module 17 defines `BuildProject.costGbp` and `ExpansionOption.costGbp`, and
the event engine already has `CardOption.costGbp` ("one-time cost charged to
the player's budget," `EventModels.kt`) — but no code anywhere tracks an
actual budget for either to be checked against. This is Module 17's Open
Question #2. This doc defines where that number lives, how it grows, and
what happens at the edges (can't afford, budget changes between prompt and
accept).

---

## Scope

**In scope**
- The budget model: what it represents, where the number comes from, how it
  replenishes
- Per-session budget state and where it lives
- Spend path: unifying `BuildProject.costGbp`/`ExpansionOption.costGbp`
  (Module 17) and `CardOption.costGbp` (event engine) under one pool
- Affordability UX contract (data only — see [Frontend Data Contract](#frontend-data-contract))
- Reconciling `docs/ux/07-planning-panel.md`'s existing £480M mockup figure
  and its "Cancel" action against Module 17's no-cancel decision

**Out of scope**
- Retail billing / customer revenue modeling (not part of the game's
  simulated economy at all — see [Design Decisions](#design-decisions--rationale) #1)
- Challenge/Tutorial mode budgets (Free Play only, same restriction as
  Module 17 and Module 13)
- Exact starting/allowance numbers beyond a first-pass default (tuning
  question, same category as Module 17 Open Question #5)

---

## Design Decisions & Rationale

1. **Fixed periodic allowance, not a fraction of dispatch revenue.** Open
   Question #2 floated three options: a fixed/periodic allowance, a fraction
   of simulated electricity revenue (closing a congestion → cost → revenue →
   capital loop), or something else. Going with the allowance model, for a
   reason specific to what this game already simulates: the player is a
   **system operator** (grid planning/operations), not a merchant generator
   or retailer — `systemMarginalCostPerMwh` (`DispatchModels.kt`) is the
   *wholesale dispatch cost signal*, not a revenue the operator collects.
   There is no billing/retail-price model in the codebase to derive
   "revenue" from, and building one just to fund a capital budget would be
   a large new subsystem for a secondary mechanic. Real transmission/
   distribution operators' capital programs are funded almost exactly this
   way in practice — a periodic regulatory allowed-revenue settlement (e.g.
   GB's RIIO price controls), not per-unit sales — so the simplified model
   is also the *more* accurate one for what the player's role actually is,
   not just the cheaper one to build.
2. **Congestion cost stays a feedback signal, not a funding source.**
   `DispatchResult.congestionCostGbp` already exists and is exactly the
   number that should make a player feel "this violation is costing me" —
   but it's surfaced as a scorecard/feedback metric (dispatch panel, and
   later Challenge mode's cost scoring per `WORK_PLAN.md` Stage 7), not
   wired into the budget itself. Keeps the two concerns — "is my grid
   running efficiently" vs. "can I afford to build" — legible as separate
   numbers rather than one entangled figure.
3. **One budget pool, not two.** `CardOption.costGbp` (event engine) and
   `BuildProject.costGbp`/`ExpansionOption.costGbp` (Module 17) currently
   have no shared concept of what they're charged against — every existing
   `CardOption.costGbp` usage is hardcoded `0.0` (see `EventEngineImpl.kt`'s
   policy-event options) specifically because there was nothing to charge.
   Both draw from the same per-session `budgetGbp`, so a player experiences
   a single coherent number regardless of which system prompted the spend.
4. **Session-scoped mutable state, same shape as the last two per-session
   fixes.** `budgetGbp` changes during play (replenishment, spend) and can't
   be static preset data — same reasoning as `13-region-unlock.md`'s
   `RegionLockState`, which is itself modeled on the per-session
   `ConcurrentHashMap<sessionId, T>` shape #347 introduced for the
   contingency-result cache. Three modules in a row landing on the same
   per-session-state idiom is worth calling out as the established pattern
   for "session-mutable, not preset-static" data going forward, rather than
   each reinventing it.

---

## Domain Model

```kotlin
/** Per-session capital budget for Free Play investment (Module 17/13 spend). */
class BudgetState(
    startingBudgetGbp: Double,
    private val allowanceGbp: Double,
    private val periodGameMinutes: Long,
) {
    var budgetGbp: Double = startingBudgetGbp
        private set
    private var lastAllowanceAtGameTimeMinutes: Long = 0L

    /** Called once per tick by the tick engine, same shape as BuildProject advancement. */
    fun advance(currentGameTimeMinutes: Long) {
        while (currentGameTimeMinutes - lastAllowanceAtGameTimeMinutes >= periodGameMinutes) {
            budgetGbp += allowanceGbp
            lastAllowanceAtGameTimeMinutes += periodGameMinutes
        }
    }

    /** True if [amountGbp] can be committed right now. Command handlers check this before spend. */
    fun canAfford(amountGbp: Double): Boolean = amountGbp <= budgetGbp

    /** Deduct immediately (funds reserved at commit time — matches the UX mockup's existing copy). */
    fun spend(amountGbp: Double) {
        check(canAfford(amountGbp)) { "Insufficient budget: have $budgetGbp, need $amountGbp" }
        budgetGbp -= amountGbp
    }
}
```

Lives alongside `RegionLockState` and the (not-yet-implemented, #414)
`BuildProject` queue in whatever per-session store ends up owning Free
Play's session-mutable state — same `ConcurrentHashMap<sessionId, T>`
pattern, created lazily on first access for that session.

`allowanceGbp`/`periodGameMinutes`/`startingBudgetGbp` are preset-level
constants (not hardcoded), read the same way `NetworkMetadataConfig`
supplies per-generator fuel/cost metadata today — a small
`FreePlayBudgetConfig`-style bean, not user-configurable in v1. **Proposed
first-pass defaults** (explicitly a tuning placeholder, not a considered
balance decision — see Open Questions): `startingBudgetGbp = 480_000_000.0`
(matches the existing `07-planning-panel.md` mockup, so the UX doc doesn't
need to change), `allowanceGbp = 150_000_000.0`, `periodGameMinutes` = one
simulated month. Chosen only so the mechanism has *some* concrete numbers to
implement and test against; real tuning needs playtesting once builds and
region-unlocks exist to spend against.

### Spend path

Both consumers validate and deduct through `BudgetState` at
command-acceptance time (not at prompt time), extending the pattern Module
17's Error Handling table already specifies for the "budget changed between
prompt and accept" case:

- **`ExpansionOption` accepted** (Module 17): command handler calls
  `budgetState.canAfford(option.costGbp)` before creating the `BuildProject`;
  reject (card stays open) if false, exactly as Module 17's Error Handling
  table already says — this doc just defines what "budget" means there.
- **`CardOption` chosen** (event engine, `EventEngineImpl`): same check
  before applying the option's effects; a decline-style option is always
  `costGbp = 0.0` and therefore always affordable, so players are never
  hard-blocked out of responding to an event card.

---

## Affordability UX

Answering Module 17's Open Question #2 sub-question directly: **show all
matched options with their real cost; disable rather than hide the
unaffordable ones.** Hiding would let a player miss that a remedy exists at
all (confusing once they've saved up); showing costs with no way to tell
affordability forces a failed-attempt round trip for the common case. A
disabled `[Build]` button (frontend, consuming `budgetGbp` from the same WS
state stream `BuildProject`/`Region.locked` will already ride on) covers
both: visible, but clearly not actionable yet. The backend still validates
independently at accept time regardless of frontend state (defense in
depth — the existing race-condition case in Module 17's Error Handling
table doesn't change).

Decline is always available and always renders enabled, regardless of
budget — matches existing `CardOption` behavior (decline options are
`costGbp = 0.0` today, and stay that way).

---

## Reconciling the existing UX mockup

`07-planning-panel.md`'s Invest tab mockup predates both this doc and
Module 17's "no cancel" decision (Module 17 Resolved Design Point #6):

- **£480M starting figure** — kept as-is; adopted directly as this doc's
  proposed `startingBudgetGbp` default (see Domain Model), so no mockup
  change needed there.
- **"Cancel" action on a Building row** — stale. Module 17 explicitly
  decided no cancel/refund (construction latency is meant to make the
  build-time cost real). The UX doc needs a follow-up edit removing the
  Cancel action from in-progress rows; flagged here rather than fixed in
  this PR since it's a UX-doc change, not an engineering one — filing as a
  small follow-up (see Open Questions).

---

## Error Handling

| Failure | Handling |
|---------|----------|
| Player accepts an `ExpansionOption`/`CardOption` but budget changed since the prompt was shown | Reject at command-validation time; card/prompt remains open (already specified in Module 17's Error Handling table — this doc supplies the check it was waiting on) |
| `BudgetState.spend` called with an amount exceeding `budgetGbp` (a bug elsewhere bypassed the `canAfford` check) | `check()` throws — fail loudly; this must never happen if command validation is correct, so treat as a bug, not a recoverable player-facing case |
| Session's `periodGameMinutes` boundary crossed multiple times in one tick (e.g. after a large speed-multiplier jump) | `advance`'s `while` loop applies the allowance once per boundary crossed, not just once — no missed or double-counted periods regardless of tick size |

---

## Testing Strategy

**Unit tests**: `BudgetState.advance` applies exactly one allowance per
period boundary crossed, including multiple boundaries in one call;
`canAfford`/`spend` boundary cases (exact-balance spend succeeds, one-`£`
over fails); `spend` throws on an unchecked over-spend attempt.

**Integration tests**: seed a Free Play session, advance the clock past one
allowance period, assert `budgetGbp` increased by exactly `allowanceGbp`;
accept an `ExpansionOption` costing more than the current budget, assert the
command is rejected and no `BuildProject` is created; accept one that fits,
assert `budgetGbp` decreases by exactly `costGbp` and the project is
created.

---

## Open Questions

1. **Real tuning of `startingBudgetGbp`/`allowanceGbp`/`periodGameMinutes`.**
   The v1 defaults above only exist to make the mechanism implementable and
   testable — needs a playtesting pass once Module 17 builds and Module 13
   region-unlocks are both live to spend against, same category as Module
   17's Open Question #5 and Module 13's Open Question #1.
2. **`07-planning-panel.md` Cancel-action cleanup.** Small, standalone UX-doc
   fix (remove the stale Cancel action on Building rows) — not blocking
   implementation of this doc, filed separately so it doesn't hold up #414.
3. **Congestion-cost-as-feedback surfacing.** This doc keeps
   `congestionCostGbp` out of the budget on purpose (Design Decision #2),
   but doesn't design *where* it's shown to the player beyond "dispatch
   panel, and later Challenge scoring" — a UX pass, not an engineering gap.

---

[[17-grid-expansion.md]] Open Question #2 is resolved by this doc.
