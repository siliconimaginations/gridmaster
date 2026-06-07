# Scene Meshes — Terrain, Grid Elements & Power Flow Particles

**Stage**: 4  
**Status**: Draft  
**Implements**: GitHub issue #79 (terrain + meshes + particle animation)  
**Depends on**:
- [11-frontend-scene.md](11-frontend-scene.md) — SceneManager, toon material pipeline, isometric camera
- [12-frontend-state-api.md](12-frontend-state-api.md) — Zustand store, `GridNetworkDto`

---

## Purpose

Replaces the placeholder ground plane with real terrain and populates the 3D scene with procedural meshes for each grid element type. After this module, the game canvas shows a recognisable power grid laid out on a landscape — generators, substations, cities, and power lines — all updating visually from live `GridNetworkDto` state.

---

## Scope

**In scope**
- Terrain: heightmap ground tiles, grass/dirt colour variation, static river mesh
- Bus positioning: hardcoded layout map for IEEE 14-bus; grid-fallback for other networks
- Procedural mesh factories for each element type: `Generator`, `Substation`, `Line` (with pylons), `City/Load`
- Status indicator rings (online / warning / fault / offline) on generators and substations
- Power flow particle system: glowing dots travelling along lines, speed ∝ MW, direction = flow direction
- Store→scene sync: `App.tsx` wires Zustand store changes to `SceneManager.updateNetwork()` on each tick

**Out of scope**
- LOD system (icon sprites at far zoom, cross-fade) — issue #80; placeholder is full mesh at all zoom levels
- Wind turbine, solar farm mesh variants — deferred to later art pass; generic generator mesh for all fuel types in MVP
- City building growth animation — deferred; city starts at correct tier for initial demand, no animated transition
- Real-time shadow maps — spec says blob shadows only; no ShadowGenerator in this issue
- Animated rotor/smoke — smoke particles deferred; status ring signals output in MVP
- Multi-level heightmap (river, roads, trees) — river mesh is static; trees and roads are deferred decorations

---

## Key Concepts / Domain Model

### World coordinate system

World units = metres (Babylon default). The scene uses a right-handed Y-up coordinate system.
The isometric camera looks down at roughly `(0, 0)` world origin.

The ground plane is 200 × 200 world units (defined in `ground.ts`). Bus positions are
mapped into `[-80, +80]` on X and Z, leaving a border for terrain features.

### Bus position mapping

`GridNetworkDto.buses` carry no spatial coordinates — the backend physics model has no
layout information. Positions are therefore determined client-side:

1. **IEEE 14-bus layout map** (`src/scene/layout/ieee14Layout.ts`): hardcoded `{ [busId: string]: { x: number; z: number } }` for the 14 known buses, placed to resemble the standard IEEE 14-bus diagram with spread-out topology. Coordinates are in world units.

2. **Grid fallback** (`src/scene/layout/gridLayout.ts`): for any network whose buses are
   not in the IEEE 14-bus map, buses are arranged in a regular grid (row-major, spacing 20 world units). Deterministic based on bus order.

Both layout functions share the signature:
```ts
function layoutBuses(buses: BusDto[]): Map<string, Vector3>
```

### Element mesh registry

A `MeshRegistry` class owns all scene meshes keyed by element ID. It is the single
authority for creating, updating, and disposing element meshes. `SceneManager` holds one
`MeshRegistry` instance.

```ts
class MeshRegistry {
  updateNetwork(network: GridNetworkDto | null): void
  dispose(): void
}
```

`updateNetwork` is idempotent — it creates meshes for new elements, updates material
colours for changed elements, and disposes meshes for removed elements. Calling it with
`null` removes all element meshes (session disconnect).

---

## Mesh Specifications

All meshes use `createToonMaterial` from `src/scene/materials/ToonMaterial.ts`.

### Generator mesh (`createGeneratorMesh`)

Single element per `GeneratorDto`. Position: bus world position.

```
  [Cylinder, r=1.5, h=4]   ← cooling tower body
  [Cylinder, r=0.3, h=0.5] ← top cap
  [Torus, r=1.8, t=0.2]    ← status ring at base (colour = status)
```

Material colours:
- Tower body: `#9ca3af` (warm grey concrete)
- Status ring: green `#4ade80` / amber `#fbbf24` / red `#f87171` / grey `#6b7280`

Status mapping (from `GeneratorDto`):
```ts
committed && activePowerMw > 0  → 'online'   (green)
committed && activePowerMw === 0 → 'warning'  (amber)
!committed                       → 'offline'  (grey)
```

Violations for this generator ID in the store → 'fault' (red), overriding the above.

### Substation mesh (`createSubstationMesh`)

Single element per unique `substationId` found in buses. Position: centroid of its buses.

```
  [Box, 4×2×4]             ← main building
  [Box, 0.2×3×0.2] × 4    ← gantry pillars at corners
  [Box, 4×0.2×0.2]         ← busbar cross-beam (top)
  [Torus, r=2.5, t=0.15]   ← status ring at base
```

Colour: steel grey `#d1d5db` body. Status ring follows same mapping as generator,
derived from voltage violations on the buses belonging to this substation.

### City/Load mesh (`createCityMesh`)

Single element per `LoadDto`. Position: bus world position (offset slightly from
generator at the same bus to avoid z-fighting: `+3` on X).

Tier is derived from `activePowerMw`:
- `< 100 MW`: 3 small box buildings (village)
- `100–500 MW`: 4 medium boxes + 1 tall box (town)
- `> 500 MW`: 5–6 tall box towers (city)

All buildings: desaturated warm beige `#fef3c7` / `#fde68a`.

Tier is re-evaluated on each `updateNetwork` call; meshes are rebuilt when tier changes.

### Transmission line mesh (`createLineMesh`)

One `LineMesh` per `BranchDto`. Composed of:
1. A thin tube mesh (`radius = 0.08`) drawn along the path between `fromBusId` and `toBusId` world positions.
2. Evenly-spaced lattice pylon meshes (one per 20 world units of line length). Each pylon is a simplified box assembly (`[Box, 0.3×4×0.3]` vertical + `[Box, 4×0.2×0.2]` crossarm).

Line colour (tube material) tracks `loadingPercent`:
- `< 70%`: white `#f9fafb`
- `70–90%`: amber `#fbbf24`
- `> 90%`: red `#f87171`

Disconnected lines (`connected === false`): colour `#6b7280` (grey), no particles.

### Power flow particles (`createFlowParticles`)

One `ParticleSystem` per active `BranchDto` (skipped when `connected === false` or `activePowerMw === 0`).

```ts
particles.emitter = fromBusPosition  // starting point
particles.particleTexture = glowDotTexture  // 8×8 white circle PNG (bundled asset)
particles.minSize / maxSize = 0.3 / 0.5
particles.emitRate = Math.max(1, activePowerMw / 50)  // 1 particle/s at 50 MW
```

Particles travel along a custom `updateFunction` that moves them from `fromBusPosition`
toward `toBusPosition` at `speed = activePowerMw / 200 + 0.1` world units per frame
(normalised to 60 fps). When a particle reaches the destination it is recycled to the
origin. Direction reverses when `activePowerMw < 0` (reactive power dominant cases).

Particle colour: soft yellow `#fef9c3` → transparent over lifetime (fade-out tail effect).

---

## Store → Scene Sync Architecture

`SceneManager` is a plain class with no React imports (as per design doc 11). The bridge
is a `useEffect` in `App.tsx` that subscribes to the Zustand store's `network` slice and
calls `SceneManager.updateNetwork()`:

```tsx
// App.tsx (addition)
useEffect(() => {
  const unsubscribe = useGameStore.subscribe(
    (state) => state.network,
    (network) => {
      managerRef.current?.updateNetwork(network)
    },
    { equalityFn: shallow, fireImmediately: true },
  )
  return unsubscribe
}, [])
```

`SceneManager` exposes the new method:
```ts
updateNetwork(network: GridNetworkDto | null): void {
  this.meshRegistry.updateNetwork(network)
}
```

The `violations` slice is also subscribed for the status ring colours:
```ts
useGameStore.subscribe(
  (state) => state.violations,
  (violations) => { managerRef.current?.updateViolations(violations) },
  { equalityFn: shallow, fireImmediately: true },
)
```

This keeps all physics in the store and all rendering in SceneManager, with App.tsx as a
thin wiring layer.

---

## File Layout

```
src/scene/
  ground.ts                  ← replace placeholder with heightmap terrain
  layout/
    ieee14Layout.ts           ← hardcoded {busId → {x,z}} for IEEE 14-bus
    gridLayout.ts             ← deterministic grid fallback
    busLayout.ts              ← selector: picks ieee14 or grid based on bus IDs
  meshes/
    generatorMesh.ts          ← createGeneratorMesh(scene, pos, dto): Mesh[]
    substationMesh.ts         ← createSubstationMesh(scene, pos, dto): Mesh[]
    cityMesh.ts               ← createCityMesh(scene, pos, dto): Mesh[]
    lineMesh.ts               ← createLineMesh(scene, from, to, dto): Mesh[]
    particleFlow.ts           ← createFlowParticles(scene, from, to, dto): ParticleSystem
    MeshRegistry.ts           ← MeshRegistry class
  __tests__/
    ieee14Layout.test.ts
    generatorMesh.test.ts     ← mesh created, correct material colour for each status
    lineMesh.test.ts          ← correct colour for each loading range
    cityMesh.test.ts          ← correct tier derived from activePowerMw
    MeshRegistry.test.ts      ← create/update/dispose lifecycle
```

---

## Design Decisions & Rationale

1. **Client-side layout, not backend coordinates.** The PowSyBl network model carries
   no spatial layout data. Adding layout to the backend would pollute the physics model
   with UI concerns. Client-side layout is the clean boundary — the same backend can
   serve multiple visual frontends.

2. **Hardcoded IEEE 14-bus layout first.** The tutorial network has a well-known
   topology. A forced-directed algorithm (e.g., D3-force) would be more general but adds
   a dependency and non-determinism. Hardcoded coordinates for the tutorial network are
   stable and fast; the grid fallback handles generated or future networks.

3. **Imperative `updateNetwork` not reactive Babylon observables.** Babylon has its own
   observable/event system, but wiring it to Zustand adds complexity. The Zustand
   `subscribe` → `updateNetwork` pattern is simpler, battle-tested, and keeps the scene
   update path synchronous and easy to test.

4. **Rebuild on tier change for cities.** City tier changes are infrequent (demand grows
   over game days, not ticks). Rebuilding a city's meshes on tier change is simpler than
   morphing geometry. The old meshes are disposed immediately to avoid memory leaks.

5. **No LOD in this issue.** The LOD cross-fade (#80) is a significant rendering
   optimisation. Deferring it means full meshes render at all zoom levels, which is
   acceptable for the tutorial network (≤ 14 buses, ≤ 20 branches).

6. **Glow dot texture bundled as asset.** The particle dot is an 8×8 white circle PNG
   stored at `public/assets/textures/glow_dot.png`. Using a texture atlas or procedural
   texture was rejected as over-engineering for a single 64-byte asset.

---

## Error Handling

- **Missing bus in layout map**: `busLayout.ts` falls through to grid layout for any bus
  ID not found in `ieee14Layout`. No exception thrown; the element renders at a grid
  position.
- **Null network on `updateNetwork(null)`**: `MeshRegistry.updateNetwork(null)` disposes
  all element meshes and particle systems. The terrain and ground remain.
- **Branch references unknown bus**: `createLineMesh` skips the branch if either
  `fromBusId` or `toBusId` is not in the position map. Logs a warning.

---

## Testing Strategy

**Unit tests** (Vitest + Babylon.js NullEngine for headless rendering):

- `ieee14Layout.test.ts`: all 14 bus IDs have positions within `[-80, 80]`; no two buses
  share the same `(x, z)` within 5 world units.
- `gridLayout.test.ts`: N buses produce N distinct positions; spacing ≥ 15 world units.
- `generatorMesh.test.ts`: committed+active → status ring material is green; offline →
  grey; mutation updates ring colour without recreating the tower mesh.
- `lineMesh.test.ts`: `loadingPercent < 70` → white tube; `> 90` → red tube.
- `cityMesh.test.ts`: `activePowerMw = 50` → 3 building meshes (village tier).
- `MeshRegistry.test.ts`: calling `updateNetwork` twice with same network is idempotent
  (mesh count does not grow); calling with `null` disposes all meshes.

All Babylon.js tests use `NullEngine` to avoid GPU dependency in CI (already established
in `ToonMaterial.test.ts` and `camera.test.ts`).

**Manual smoke test checklist** (follows QA scenarios SL-01, GC-01, HUD-01):
1. `docker compose up` → navigate to `http://localhost:5173`
2. Canvas shows green ground plane with terrain variation
3. After session creation + WS connect: 14 bus positions rendered with generator/substation/city meshes
4. Lines visible between connected buses
5. HUD clock increments; line colours change if load increases past 70%
6. Toggling a generator offline → status ring turns grey within one tick

---

## Open Questions

| # | Question | Owner | Target |
|---|----------|-------|--------|
| 1 | Should the IEEE 14-bus hardcoded layout be stored in the backend (as metadata on the preset) or remain purely client-side? | Rick | Before implementing |
| 2 | Is the `glow_dot.png` asset bundled in the repo, or should it be generated procedurally at runtime using a Babylon `DynamicTexture`? Procedural removes an asset file; bundled is simpler. | Claude | During implementation — default to procedural unless Rick says otherwise |
