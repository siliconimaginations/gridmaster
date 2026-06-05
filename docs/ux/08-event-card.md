# UX: Event Cards & N-1 Panel

**Stage**: 1 | **Status**: Draft

---

## Purpose

Panels that fire when the game raises an event: N-1 contingency risk, weather disruptions, fuel price spikes, policy cards, and network failures. All follow the same panel pattern; colour and content vary by severity and type.

---

## Shared panel pattern

Triggered by clicking a toast, the Health pill, or an amber/red action button in the bottom HUD.

```
┌─ [COLOUR HEADER] ──────────────────────────────────────┐
│  [Icon]  Event title                    [×]             │
│          Short one-line description                     │
├────────────────────────────────────────────────────────┤
│  Causal flow strip                                      │
│  [Cause] ──▶ [Mechanism] ──▶ [Outcome]                  │
├────────────────────────────────────────────────────────┤
│  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│  │ Metric 1 │  │ Metric 2 │  │ Metric 3 │             │
│  │  118%    │  │ 348 A    │  │ £2.1M    │             │
│  └──────────┘  └──────────┘  └──────────┘             │
├────────────────────────────────────────────────────────┤
│  Current load  ──────────────[now]──[after]──  Rating  │
│  [████████████████░░░░░░░░░░░░░░░░░░░░░░░░░░]  400 A  │
├────────────────────────────────────────────────────────┤
│  ┌──────────────────────┐  ┌──────────────────────┐   │
│  │ [icon] Option A       │  │ [icon] Option B       │   │
│  │ Short title   [tag]  ⓘ│  │ Short title   [tag]  ⓘ│   │
│  └──────────────────────┘  └──────────────────────┘   │
├────────────────────────────────────────────────────────┤
│  [Apply selected option]         Learn more ↗           │
└────────────────────────────────────────────────────────┘
```

Header colour: red = violation/failure · amber = risk/warning · green = resolved.

---

## N-1 contingency risk

Fires when background N-1 analysis detects a post-contingency violation.

| Field | Value |
|-------|-------|
| Header | Amber |
| Title | N-1 Risk: CCGT-1 loss |
| Causal flow | CCGT-1 trips → power redistributes → Line L3 overloads at 118% |
| Metrics | Line L3 loading: 118% · Current: 468 A · Risk cost: £2.1M |
| Loading bar | Now: 87% · After CCGT-1 loss: 118% · Rating: 400 A |
| Options | Redispatch CCGT-2 (+80 MW) · Add line rating override (temporary) |

---

## Network failure

Fires when a real-time violation occurs and islanding/blackout results.

| Field | Value |
|-------|-------|
| Header | Red |
| Title | Grid Failure — Northgate islanded |
| Causal flow | Line L2 tripped → Northgate lost supply → 320 MW curtailed |
| Metrics | Load curtailed: 320 MW · Cities affected: 3 · Cost/min: £48k |
| Options | Restore via Line L5 · Reconnect via manual switching |

---

## Weather event

| Field | Value |
|-------|-------|
| Header | Amber |
| Title | Storm — East Region |
| Causal flow | Wind forecast +40% → possible line galloping → trip risk in 2 h |
| Metrics | Wind ramp: +400 MW · Trip risk: 3 lines · Confidence: 74% |
| Options | Pre-emptive redispatch · Accept risk |

---

## Fuel / price event

| Field | Value |
|-------|-------|
| Header | Amber |
| Title | Gas price spike · +35% for 6 h |
| Causal flow | Spot gas +35% → CCGT marginal cost rises → system price £72/MWh |
| Metrics | Price now: £72/MWh · Extra cost/h: £180k · Duration: 6 h |
| Options | Switch to coal backup · Curtail industrial demand |

---

## Policy card

| Field | Value |
|-------|-------|
| Header | Blue (info) |
| Title | New offer: Renewable subsidy |
| Causal flow | Government offer → £15/MWh subsidy → reduces solar/wind cost |
| Metrics | Eligible capacity: 500 MW · Subsidy value: £7.5M/yr · Expires: Day 60 |
| Options | Accept subsidy · Decline |

---

## Open questions

None.
