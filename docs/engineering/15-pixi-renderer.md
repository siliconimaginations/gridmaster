# 15 — PixiJS Isometric Renderer

**Status:** Planned (replaces Babylon.js)  
**Issues:** #311 (main), #312 (data model), #313 (layout), #314 (LOD), #315 (particles)

---

## Why PixiJS

The UX direction shifted from real-3D (Babylon.js meshes) to pseudo-3D isometric (sprite-based 2D). Babylon.js is the wrong tool for a 2D renderer:

| Concern | Babylon.js | PixiJS |
|---------|-----------|--------|
| Render path | WebGL1/2, full 3D pipeline | WebGL2, optimised 2D batching |
| Sprites | Planes with textures | First-class `PIXI.Sprite` |
| 1000+ moving particles | Expensive (draw calls per mesh) | `ParticleContainer` = 1 draw call |
| Bundle size | ~800 kB | ~300 kB |
| Zoom/pan | 3D camera | `pixi-viewport` (2D, touch-ready) |
| Terrain tiling | Repeating UV plane | `TilingSprite` |

---

## Architecture

### What changes vs. what stays

```
┌──────────────────────────────────────────────────────┐
│  App.tsx  (unchanged)                                │
│    ├── Zustand store  (unchanged)                    │
│    ├── TopHud / BottomHud / InspectorPanel … (unch.) │
│    └── <GridCanvas />   ← NEW (replaces raw canvas + │
│                               SceneManager)          │
└──────────────────────────────────────────────────────┘
```

`SceneManager.ts` and the `scene/` directory are replaced by:

```
src/
  renderer/
    PixiGridRenderer.ts   ← main renderer class
    layers/
      TerrainLayer.ts     ← TilingSprite terrain
      NodeLayer.ts        ← bus sprites, painter-sorted
      WireLayer.ts        ← catenary Graphics lines
      ParticleLayer.ts    ← flow ParticleContainer
    layout/
      GridGraph.ts        ← data model (see §Data model)
      autoLayout.ts       ← layout algorithm (see §Layout)
    lod/
      LodController.ts    ← zoom → LOD tier (see §LOD)
  components/
    GridCanvas.tsx        ← React wrapper for PixiApp
```

### Render loop

```typescript
// PixiGridRenderer.ts
class PixiGridRenderer {
  private app: PIXI.Application
  private viewport: Viewport          // pixi-viewport
  private terrain: TerrainLayer
  private nodes: NodeLayer
  private wires: WireLayer
  private particles: ParticleLayer
  private lod: LodController

  // Called by App.tsx on store change (same API as SceneManager)
  updateNetwork(network: NetworkDto, violations: ViolationDto[]): void
  updateViolations(violations: ViolationDto[]): void
  dispose(): void
}
```

### Layer order (painter's algorithm)

```
viewport
  └── world (Container, sortableChildren = false — layers handle internal sort)
        ├── terrain   z=0   TilingSprite × 2 (staggered diamond tiles)
        ├── wires     z=10  Graphics (catenary curves, drawn below sprites)
        ├── nodes     z=20  Container (sortableChildren=true, zIndex=bus.y)
        ├── particles z=30  ParticleContainer (flow dots, toggle opacity)
        └── (labels: HTML overlay, not PixiJS — see §Labels)
```

---

## Data Model (`GridGraph.ts`)

```typescript
export interface BusNode {
  id: number
  type: 'gen' | 'sub' | 'load'
  x: number; y: number          // canvas coords — set by layout algorithm
  lat?: number; lon?: number     // geographic coords when available
  name: string
  voltageLevel: number           // kV nominal (used for LOD grouping)
  v: number                      // per-unit voltage (0.85–1.15 typical)
  mw: number; maxMw: number
  status: 'Normal' | 'Warning' | 'Critical'
}

export interface BranchEdge {
  id: number
  fromId: number; toId: number
  loadFactor: number             // 0–1 (fraction of thermal rating)
  ratingMva: number
}

export interface GridGraph {
  buses: Map<number, BusNode>
  edges: BranchEdge[]
  adjacency: Map<number, Set<number>>   // O(1) neighbour lookup
}

export function networkDtoToGridGraph(dto: NetworkDto): GridGraph
```

The conversion helper `networkDtoToGridGraph` replaces all ad-hoc array-to-object mappings currently scattered across the scene code.

---

## Layout Algorithm (`autoLayout.ts`)

```typescript
export function layoutGrid(
  graph: GridGraph,
  width: number,
  height: number,
): GridGraph
```

Priority order:

1. **Geographic** — if ≥80% of buses have `lat/lon`, project via Mercator, fit to `(width × 0.9, height × 0.9)` with padding.
2. **Force-directed** — `d3-force` with:
   - `forceLink` (edge spring, distance proportional to `1 / loadFactor`)
   - `forceManyBody` (repulsion, strength −300)
   - `forceCenter` anchored at viewport centre
   - Voltage-level grouping: `forceY` pulls generators up, loads down
3. **Fallback** — circular layout.

Layout runs once per grid load and on viewport resize. Positions written into `BusNode.x / .y`.

---

## Zoom / Pan (`pixi-viewport`)

```typescript
const viewport = new Viewport({
  screenWidth: window.innerWidth,
  screenHeight: window.innerHeight,
  worldWidth: WORLD_W,
  worldHeight: WORLD_H,
  events: app.renderer.events,
})
viewport.drag().pinch().wheel().decelerate()
viewport.clampZoom({ minScale: 0.15, maxScale: 4 })
```

`viewport.on('zoomed', () => lod.update(viewport.scale.x))` drives LOD tier changes.

---

## LOD Tiers (`LodController.ts`)

| Tier | Zoom | Buses | Labels | Voltage dots | State bars | Wires |
|------|------|-------|--------|-------------|------------|-------|
| 0 | < 0.35× | Aggregate zone chips | Zone names only | — | — | Thick solid |
| 1 | 0.35–0.70× | Small sprites | Bus ID | ✓ | — | Normal catenary |
| 2 | > 0.70× | Full sprites | ID + name | ✓ | ✓ (gen only) | Full catenary |

LOD changes are applied without re-creating sprites:
- Sprite `scale` interpolated via GSAP tween
- Label HTML elements toggled with `visibility: hidden` (keeps layout stable)
- State bar `Container.visible = tier >= 2`

---

## Voltage Dot & State Bar

### Voltage dot (all bus types)
Drawn in `NodeLayer` as a `PIXI.Graphics` circle at `(-30, wireOffsetY - 4)` in local coords. Color from 5-zone scheme:

| Range | Color |
|-------|-------|
| v < 0.90 | `#bb66ff` light purple (critical low) |
| 0.90–0.95 | `#4488ff` blue |
| 0.95–1.05 | `#28cc60` green (normal) |
| 1.05–1.10 | `#ff8822` orange |
| v > 1.10 | `#ff3030` red (critical high) |

### MW state bar (generators only)
`PIXI.Graphics` rectangle 64×8px above sprite top. Threshold markers at 60% and 85%. Colors: green / amber / red.

---

## Catenary Wires

```typescript
// Pre-compute per edge at layout time
interface WireGeometry {
  ax: number; ay: number   // from-bus rooftop
  bx: number; by: number   // to-bus rooftop
  cx: number; cy: number   // bezier control (midpoint + droop)
}

function catenaryGeom(from: BusNode, to: BusNode): WireGeometry {
  const dist = Math.hypot(to.x - from.x, to.y - from.y)
  const droop = Math.min(dist * 0.07, 28)
  return {
    ax: from.x, ay: from.y + WIRE_OFFSET[from.type],
    bx: to.x,   by: to.y   + WIRE_OFFSET[to.type],
    cx: (from.x + to.x) / 2,
    cy: (from.y + to.y) / 2 + WIRE_OFFSET_AVG + droop,
  }
}
```

`WireLayer` calls `graphics.moveTo / quadraticCurveTo` for each edge. Re-drawn on `updateNetwork`; violations trigger color-only update via `updateViolations` (fast path, no curve re-computation).

---

## Flow Particles (`ParticleLayer.ts`)

```typescript
// Pre-computed Bezier LUT per edge (64 samples)
type BezierLUT = Float32Array  // [x0,y0, x1,y1, … x63,y63] = 128 floats

interface Particle {
  sprite: PIXI.Sprite
  lut: BezierLUT
  t: number        // 0–1 position along curve
  speed: number    // advances per 16ms tick
}
```

Tick function (called from `app.ticker`):
```typescript
for (const p of this.particles) {
  p.t = (p.t + p.speed) % 1
  const i = Math.floor(p.t * 63) * 2
  p.sprite.position.set(p.lut[i], p.lut[i + 1])
}
```

`ParticleContainer` batches all particles into a single instanced draw call regardless of count.

---

## Labels (HTML Overlay)

Labels live outside PixiJS in an absolutely-positioned `<div id="label-overlay">` with `pointer-events: none`. On each frame (or on pan/zoom), bus screen positions are computed:

```typescript
const screenPos = viewport.toScreen(bus.x, bus.y)
labelEl.style.transform = `translate(${screenPos.x}px, ${screenPos.y}px)`
```

Benefits over `PIXI.Text`:
- Native font rendering (subpixel AA)
- DOM tooltips (hover card) work without PixiJS hit-testing
- CSS transitions for LOD fade-in/out
- Accessible to screen readers

Label elements are pooled (one `<div>` per bus, created once, repositioned each frame).

---

## Migration Plan

| Step | PR | Scope |
|------|-----|-------|
| 1 | — | Add `pixi.js` + `pixi-viewport` to package.json; remove `@babylonjs/*` |
| 2 | — | `GridGraph.ts` + `networkDtoToGridGraph` + unit tests |
| 3 | — | `autoLayout.ts` + unit tests |
| 4 | — | `PixiGridRenderer.ts` skeleton (terrain + static sprites, no anim) |
| 5 | — | `WireLayer` + `ParticleLayer` |
| 6 | — | `LodController` + label overlay |
| 7 | — | Remove Babylon.js; update `App.tsx` |
| 8 | — | E2E smoke tests |

Steps 1–4 can land behind a feature flag (`VITE_RENDERER=pixi`) so Babylon.js and PixiJS coexist during migration.

---

## Open Questions

- **Sprite atlas**: single `sprites.json` atlas sheet vs individual PNGs. Atlas is faster (1 texture upload) but harder to update per asset.
- **High-DPI**: PixiJS `resolution = window.devicePixelRatio` handles retina; terrain tiles need to be 2× resolution or filtered with `SCALE_MODE.LINEAR`.
- **Server-push layout**: if the backend sends bus coordinates (e.g., from a GIS layer), skip the client-side layout algorithm entirely.
