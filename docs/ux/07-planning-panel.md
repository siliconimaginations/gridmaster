# UX: Planning Panel

**Stage**: 1 | **Status**: Draft

---

## Purpose

Forward-looking decisions: build new generation, upgrade transmission, and review N-1 security table. Available in Challenge and Free Play modes; locked during Tutorial until mission 5.

Opened via the "Plan day" button in the bottom HUD action strip.

---

## Panel anatomy

Slides up from the bottom, covering ~50% of screen height. Three tabs.

```
┌──────────────────────────────────────────────────────────┐
│ Planning                [Invest]  [N-1 Table]  [Forecast] │
└──────────────────────────────────────────────────────────┘
```

---

## Invest tab — investment queue

```
┌──────────────────────────────────────────────────────────────────────┐
│ Available budget: £480M                                               │
├──────────┬──────────────────────┬──────────┬───────────┬────────────┤
│ Status   │ Project              │ Cost     │ Build time│ Action     │
├──────────┼──────────────────────┼──────────┼───────────┼────────────┤
│ ✓ Built  │ Solar Farm B         │ £120M    │ Done      │ —          │
│ ⚙ Building│ CCGT-2              │ £200M    │ 18 days   │ Cancel     │
│ 💡 Option │ Offshore Wind X     │ £350M    │ 24 days   │ [Build]    │
│ 💡 Option │ Battery Storage 1   │ £80M     │ 7 days    │ [Build]    │
│ 💡 Option │ Line Upgrade L4     │ £60M     │ 5 days    │ [Build]    │
└──────────┴──────────────────────┴──────────┴───────────┴────────────┘
```

- Budget bar at the top depletes as projects are queued
- **[Build]** button commits the project; funds reserved immediately
- Each option has a ⓘ tooltip: capacity, fuel type, grid impact summary
- "Building" rows show a progress bar in the Status cell

---

## N-1 Table tab

Compact table of all single-contingency risks. Same data surface as the N-1 panel (see `08-event-card.md`), shown in tabular form for planning context.

```
┌──────────────────┬────────────────┬───────────┬───────────────────┐
│ Lost element     │ Overloaded     │ Loading   │ Fix               │
├──────────────────┼────────────────┼───────────┼───────────────────┤
│ CCGT-1 (1000MW)  │ Line L3        │ 118% ⛔   │ Add line / reduce │
│ Line L2          │ Sub B voltage  │ 0.91 pu ⚠ │ Add shunt cap     │
│ Wind Park W      │ — (ok)         │ —         │ —                 │
└──────────────────┴────────────────┴───────────┴───────────────────┘
```

- Red row = security violation post-contingency
- Amber row = near-limit warning
- Click any row → highlights the affected elements on the map

---

## Forecast tab

Demand and renewable output forecast for the next 7 days. Line chart, two series:

- **Grey area**: total demand forecast ± uncertainty band
- **Green line**: renewable output forecast (wind + solar)
- **Gap** between them = dispatchable generation needed

Hover any point → tooltip with exact MW values and date/time.

---

## Open questions

None.
