# UX: Dispatch Panel

**Stage**: 1 | **Status**: Draft

---

## Purpose

The dispatch panel lets players set generator output levels and manage the merit order. It covers two views: **real-time dispatch** (right now) and **UC schedule** (next 24 h).

Opened via the "Dispatch" button in the bottom HUD action strip.

---

## Panel anatomy

Slides up from the bottom, covering ~40% of screen height. Map remains visible above it.

```
┌─────────────────────────────────────────────────────┐
│ Dispatch                    [Real-time] [Day-ahead]  │  ← tabs
└─────────────────────────────────────────────────────┘
```

---

## Real-time tab — merit order table

Lists all online + committed generators sorted by marginal cost (cheapest first).

```
┌──┬──────────────┬──────────┬──────────┬───────────┬──────────┐
│  │ Generator    │ Type     │ Cost     │ Output    │ Headroom │
├──┼──────────────┼──────────┼──────────┼───────────┼──────────┤
│ 1│ Solar Farm A │ 🌞 Solar │ £0/MWh   │ 180 MW ▓▓▓│ +0 MW    │
│ 2│ Wind Park W  │ 💨 Wind  │ £4/MWh   │ 320 MW ▓▓▓│ +80 MW   │
│ 3│ CCGT-1       │ 🔥 Gas   │ £48/MWh  │ 880 MW ▓▓▓│ +120 MW  │
│ 4│ Coal Plant C │ ⚫ Coal  │ £55/MWh  │ 600 MW ▓▓▓│ +200 MW  │
│ 5│ Peaker-1     │ 🔥 Gas   │ £89/MWh  │ 0 MW      │ +150 MW  │
└──┴──────────────┴──────────┴──────────┴───────────┴──────────┘
                                          Total: 1 980 MW / 2 100 MW demand
```

- Output bar: inline mini-bar, fills proportional to max capacity; colour = type
- **Adjust output**: click any row → inline slider appears for that row (min–max range)
- **Commit / decommit**: toggle button per row; decommitted generators drop to bottom
- Row turns amber if generator is near thermal limit; red if violated

---

## Day-ahead tab — UC schedule grid

24-column hour grid. One row per generator.

```
         │ 0h │ 1h │ ... │ 8h │ 9h │ ... │ 23h │
─────────┼────┼────┼─────┼────┼────┼─────┼─────┤
CCGT-1   │ ON │ ON │ ... │ ON │ ON │ ... │ ON  │
Coal C   │ ON │ ON │ ... │ ON │ ON │ ... │ OFF │
Peaker-1 │    │    │ ... │    │ ON │ ... │     │
─────────┴────┴────┴─────┴────┴────┴─────┴─────┘
         ▓▓▓▓▓▓▓▓  ← demand forecast bar below grid
```

- ON cells: green fill; OFF: empty; click toggles
- Demand forecast bar below shows expected load curve
- Red cell border = unit commitment constraint violated (e.g., min up/down time)
- **Auto-fill** button: fills remaining uncommitted slots using merit order; player can override

---

## Footer

```
│ System cost today: £1.24M   Marginal price now: £48.20/MWh   [Close] │
```

---

## Open questions

None.
