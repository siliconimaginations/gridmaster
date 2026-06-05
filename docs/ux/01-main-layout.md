# UX: Main Layout

**Stage**: 1 | **Status**: Draft | **Branch**: `stage/1/ux-design-docs`

---

## Layout structure

Full-screen game canvas. All UI floats over the map — no fixed side panels.

```
┌─────────────────────────────────────┐
│  [TOP HUD — pill badges]            │
│                                     │
│                                     │
│       3D Babylon.js map             │  ← fills 100% of screen
│       (terrain, cities, grid)       │
│                              [TOASTS│
│                               stack]│
│  [BOTTOM HUD — clock + actions]     │
└─────────────────────────────────────┘
```

Clicking any map element → inspector popup card (floats near the element).

---

## Top HUD

Single horizontal strip, always visible, semi-transparent pill badges.

| Pill | Content | Colour signal |
|------|---------|--------------|
| Clock | Day 47 · 14:20 | — |
| Load | 1 831 MW total | — |
| Price | £48.20/MWh | green <£60 · amber £60–100 · red >£100 |
| Health | Grid healthy / N-1 risks / Failure | green · amber · red |

Health pill is the only badge that changes colour. Clicking it opens the N-1 table panel.

---

## Bottom HUD

Two sections separated by flex gap:

**Left — clock controls**
- Speed label (current multiplier)
- Speed buttons: 1× · 10× · 60× · 100× (active = green fill)
- Play/pause toggle (circular button)

**Right — contextual action buttons**
Shown based on game state. Max 4 visible at once; overflow hidden behind a "…" button.

| Trigger | Button shown |
|---------|-------------|
| Always | Dispatch · Plan day |
| N-1 risk present | N-1 check (amber) |
| Active event | Event name (coloured to severity) |
| Network failure | Restore (red, pulsing) |

---

## Alert toasts

Right side, stacked above bottom HUD. Max 3 visible; older toasts pushed off.

- **Critical** (red border): stays until player acts
- **Warning** (amber border): stays 60 game-minutes, then auto-dismisses
- **Info** (blue border): auto-dismisses after 30 game-minutes
- Tapping a toast opens the relevant detail panel

---

## Inspector popup

Appears on map element click. Floats near the clicked element (repositions if near edge).
Contains: element name + icon, 3–5 key metrics, 1–2 action buttons.
Dismissed by clicking elsewhere on the map.
Detailed spec: [02-component-inspector.md](02-component-inspector.md)

---

## Map composition principles

- Isometric camera, fixed angle, pan + pinch-zoom
- Terrain: hills, river, roads — warm palette, daylight
- Grid elements sit *in* the world (not overlaid on a diagram)
- Power lines: thin cartoon cables strung between pylons
- Line loading → line colour: white (0–70%) · amber (70–90%) · red (>90%)
- Power flow: animated particle dots moving along lines, speed ∝ MW
- Cities/towns scale with demand (small village → large city as game progresses)

Art direction detail: [09-scene-visual-spec.md](09-scene-visual-spec.md)

---

## Responsive behaviour

Game targets desktop (1280×720 min). Panels do not reflow below 900px width — show a "please use a larger screen" overlay below that threshold.

---

## Open questions

None.
