# UX: Time Axis & Clock Controls

**Stage**: 1 | **Status**: Draft

---

## Purpose

Players control simulated grid time: pause, step, and speed up to compress days into minutes. The time axis also shows scheduled events and day-ahead commitment windows.

---

## Clock display (bottom HUD — left section)

```
┌──────────────────────────────────────────────┐
│  Day 47 · 14:20   ⏸  1×  10×  [60×]  100×   │
└──────────────────────────────────────────────┘
```

- **Day + time**: mirrored in top HUD pill; canonical source is the game clock
- **Play/pause**: circular button, toggles ▶ / ⏸
- **Speed buttons**: 1×, 10×, 60×, 100× — active has green fill, others muted

| Multiplier | Real-time per tick | 1 grid-day |
|------------|-------------------|-----------|
| 1× | 10 s | ~24 min |
| 10× | 1 s | ~2.4 min |
| 60× | 167 ms | ~24 s |
| 100× | 100 ms | ~14 s |

Speed auto-reduces to 1× when a critical event fires; player can re-raise it.

---

## Day-ahead timeline strip

Appears when "Plan day" is open. Thin strip directly above the bottom HUD.

```
│ Now ▼                                                              │
│─ 0h ─ 2h ─ 4h ─ 6h ─ 8h ─ 10h ─ 12h ─ 14h ─ 16h ─ 18h ─ 20h ─ 22h ─│
│  ████████  ████████   ░░░░░░░░   ████████                          │
│                             ▲ demand peak                          │
```

- Green fill = UC schedule committed for that hour block
- Grey hatching = uncommitted; nudges player to act
- Amber triangle = forecasted demand peak hour
- Click any hour block → opens UC schedule view for that slot

Strip is hidden in Free Play until the player unlocks the Day-ahead planning feature.

---

## Event markers

Coloured triangles on the timeline strip, just above the hour labels:

| Marker | Meaning |
|--------|---------|
| Red ▲ | Network failure (past or imminent) |
| Amber ▲ | Active weather or fuel event window |
| Blue ▲ | Policy card in effect |

Hover shows tooltip: event name + time window.

---

## Open questions

None.
