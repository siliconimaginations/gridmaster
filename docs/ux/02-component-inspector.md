# UX: Component Inspector

**Stage**: 1 | **Status**: Draft

---

## Purpose

Clicking any element on the 3D map opens a compact popup card with key stats and 1–2 quick actions. Replaces side-panel detail views — keeps the map visible.

---

## Trigger & placement

- Click on: generator, substation, line, load (city/town), transformer
- Popup floats near the clicked element; auto-repositions away from screen edges
- Dismissed by clicking anywhere else on the map or the × button
- Only one inspector open at a time

---

## Card anatomy

```
┌────────────────────────────┐
│ [icon] Element name     [×]│  ← header: icon + name, tinted by status
├────────────────────────────┤
│ Label       Value          │  ← 3–5 metric rows
│ Label       Value ⚠        │  ← amber/red value = at-limit indicator
│ ...                        │
├────────────────────────────┤
│ [Action]     [Action]      │  ← max 2 buttons
└────────────────────────────┘
```

Header tint: green (healthy) · amber (warning) · red (violation/failure).

---

## Per-element content

### Generator
| Row | Example |
|-----|---------|
| Output | 880 MW (88%) |
| Min / Max | 600 / 1 000 MW |
| Cost | £12/MWh |
| Status | Online / Offline / Fault |

Actions: **Adjust output** · **Decommit**

### Line
| Row | Example |
|-----|---------|
| Loading | 87% ⚠ |
| Current | 348 A |
| Rating | 400 A |
| Flow | 210 MW → |

Actions: **Trip** · **N-1 impact ↗**

### Substation / Bus
| Row | Example |
|-----|---------|
| Voltage | 0.98 pu |
| Connected lines | 4 |
| N-1 status | 2 risks ⚠ |

Actions: **View N-1 ↗**

### City / Load
| Row | Example |
|-----|---------|
| Demand | 620 MW |
| Growth | +2.1%/yr |
| Supply status | Supplied ✓ |

Actions: **Curtail demand ↗**

### Transformer
| Row | Example |
|-----|---------|
| Loading | 62% |
| HV / LV | 400 kV / 132 kV |
| Tap position | +2 |
| Status | Normal |

Actions: **Adjust tap** · **Trip**

---

## Open questions

None.
