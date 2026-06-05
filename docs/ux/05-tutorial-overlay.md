# UX: Tutorial Overlay

**Stage**: 1 | **Status**: Draft

---

## Purpose

Guided instruction layer for Tutorial mode. Walks players through 8 missions without obscuring the map. Uses spotlight + instruction card to direct attention; does not pause the simulation unless the mission requires it.

---

## Overlay components

### Instruction card

Floating card, anchored bottom-left above the bottom HUD. Never covers map centre.

```
┌────────────────────────────────────┐
│ Mission 2 — Power Flow        2/8  │  ← header: mission name + progress
├────────────────────────────────────┤
│ [Objective icon]                   │
│ Short objective text (1–2 lines)   │
├────────────────────────────────────┤
│ ✓ Step 1 complete                  │  ← checklist (max 4 steps per mission)
│ → Step 2: click Line L3            │
│   Step 3: observe loading %        │
├────────────────────────────────────┤
│ [Hint ⓘ]            [Skip →]       │
└────────────────────────────────────┘
```

- Progress badge top-right: `2/8` mission counter
- Active step shown with arrow `→`, completed with `✓`, future greyed out
- **Hint ⓘ**: expands one-line contextual tip inline (not a modal)
- **Skip →**: skips to next mission; confirms via a single "Are you sure?" toast (not modal)

### Spotlight

Semi-transparent dark overlay with a cutout around the target element. Used for "click this" steps.

- Cutout shape follows the element: rectangular for HUD buttons, elliptical for map elements
- Pulsing white border on the cutout to draw the eye
- Clicking outside the spotlight during a locked step plays a gentle shake animation

### Objective checklist panel (optional, mission-specific)

Some missions show a persistent checklist panel (right edge, mid-screen) listing sub-goals with live status ticks. Only shown when the mission has 3+ concurrent objectives.

---

## Mission flow

1. Mission intro card slides in (2 s), pauses game
2. Player reads, clicks **Start** → game resumes, card shrinks to corner
3. Steps complete one by one (auto-detected by game events)
4. On final step complete → **Mission complete** banner (full-width, 3 s, then auto-dismiss)
5. Next mission card slides in automatically

---

## Tutorial missions

| # | Mission | Key interaction |
|---|---------|----------------|
| 1 | Grid anatomy | Click every element type on the map |
| 2 | Power flow | Observe line loading; trace generation to load |
| 3 | Operating limits | Trigger a line overload; restore |
| 4 | Generator dispatch | Adjust output; meet demand |
| 5 | Contingency analysis | Open N-1 panel; read risk table |
| 6 | Economic dispatch | Sort merit order; commit cheapest set |
| 7 | Unit commitment | Fill day-ahead schedule for 24 h |
| 8 | Dynamics & stability | Respond to sudden generator trip |

---

## Open questions

None.
