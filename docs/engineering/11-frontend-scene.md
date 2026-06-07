# Frontend Scene Foundation

## Purpose

Establishes the Babylon.js 7 engine, toon-shading material pipeline, and isometric camera as the visual foundation for all game rendering. Every game element added in subsequent issues (terrain, meshes, particles, LOD) builds on top of this module.

---

## Scope

**In scope (issue #78)**
- Babylon.js `Engine` + `Scene` lifecycle wired to the React canvas
- Isometric `ArcRotateCamera` (pan + zoom; rotation locked)
- Toon-shade material pipeline: cel-diffuse ramp + back-face outline pass
- Scene lighting (directional sun + ambient fill)
- MSAA 4× anti-aliasing
- `src/scene/` directory scaffold
- Static ground plane (placeholder terrain — real terrain is issue #79)

**Out of scope**
- Game element meshes (generators, lines, substations) — issues #79, #80
- Power-flow particles — issue #79
- Zustand state integration / WebSocket — issues #81, #82
- HUD panels and React UI — issues #83–#89
- LOD sprite fallback — issue #80

---

## Key Concepts / Domain Model

### Scene module boundary

```
src/
  scene/
    SceneManager.ts      — engine + scene init, render loop, resize handling
    camera.ts            — isometric ArcRotateCamera factory + input config
    lighting.ts          — sun DirectionalLight + HemisphericLight factory
    materials/
      ToonMaterial.ts    — cel-shade NodeMaterial factory (reused per element type)
      OutlineMaterial.ts — back-face expansion pass for cartoon outlines
    ground.ts            — placeholder ground plane
  ui/                    — React components (no Babylon imports)
  state/                 — Zustand store
  api/                   — WebSocket + REST clients
  App.tsx                — mounts canvas, bootstraps SceneManager
```

`src/scene/` owns all Babylon engine interaction. React components in `src/ui/` never import `@babylonjs/*` directly.

### SceneManager

Singleton class (not React state) that holds references to the `Engine`, `Scene`, camera, and lights. Exposed via a module-level ref in `App.tsx`. The render loop runs inside `SceneManager`; React only provides the canvas element.

```ts
class SceneManager {
  readonly engine: Engine
  readonly scene: Scene
  dispose(): void
}
```

---

## API / Interface

### SceneManager

```ts
// src/scene/SceneManager.ts

export class SceneManager {
  constructor(canvas: HTMLCanvasElement)
  /** Start the Babylon render loop. */
  start(): void
  /** Stop the render loop and release all GPU resources. */
  dispose(): void
}
```

### ToonMaterial

```ts
// src/scene/materials/ToonMaterial.ts

/**
 * Returns a Babylon NodeMaterial configured with a 3-step toon diffuse ramp.
 * All game elements share one cached instance per element type.
 */
export function createToonMaterial(scene: Scene, baseColor: Color3): NodeMaterial
```

### Camera

```ts
// src/scene/camera.ts

/**
 * Creates an isometric ArcRotateCamera locked to 30° pitch / 45° yaw.
 * Pan and zoom are enabled; rotation is disabled.
 */
export function createIsometricCamera(scene: Scene, canvas: HTMLCanvasElement): ArcRotateCamera
```

---

## Design Decisions & Rationale

### 1. SceneManager as plain class, not React state

Babylon's `Engine` and `Scene` are heavyweight mutable objects. Storing them in React state would trigger unnecessary re-renders and risk double-initialization under `StrictMode`. A module-level singleton class managed in an `App.tsx` `useRef` is the standard pattern for integrating imperative rendering APIs with React.

### 2. Toon shader via NodeMaterial (NME)

Babylon.js 7 ships a Node Material system that compiles to WebGL shaders. Three alternatives were considered:

| Option | Pros | Cons | Decision |
|--------|------|------|----------|
| `StandardMaterial` + ramp texture | Simple | No outline support, hard to extend | Rejected |
| Custom raw GLSL shader | Full control | Fragile, bypasses Babylon pipeline | Rejected |
| `NodeMaterial` (NME) | Babylon-native, supports PBR extensions, IDE-editable | Slightly more setup | **Chosen** |

The toon diffuse step is implemented as: `step(0.5, dot(N, L))` clamped to 3 bands (dark shadow, mid, highlight). Material instances are cached per element type.

### 3. Outlines via back-face expansion

Thick cartoon outlines are rendered using a second mesh pass: the mesh is scaled by `1 + outlineWidth`, normals are flipped, and a flat black material is applied. This renders reliably across all hardware without requiring stencil-buffer tricks or post-processing FXAA dependencies.

Alternative (post-process outline via Sobel filter) was rejected: it interacts poorly with transparent UI elements overlaid on the canvas.

### 4. Isometric camera: ArcRotateCamera with locked rotation

`ArcRotateCamera` with:
- `alpha = -Math.PI / 4` (45° yaw)
- `beta = Math.PI / 5` (~36° from zenith ≈ 54° from horizon)
- `lowerBetaLimit = upperBetaLimit = Math.PI / 5` (locks vertical angle)
- `lowerAlphaLimit = upperAlphaLimit = -Math.PI / 4` (locks horizontal angle)
- Panning via `camera.inputs.addMouseWheel()` removed; pointer move pan enabled
- `lowerRadiusLimit = 5`, `upperRadiusLimit = 120` (zoom range)

Rotation is fully disabled to preserve the isometric perspective the UX spec requires.

### 5. MSAA 4× on Engine constructor

Passed as the `antialias: true` option to `new Engine(canvas, true)` and enabled via `engine.setHardwareScalingLevel(1)`. The scene's `postProcessesEnabled` remains true for future post-process additions (bloom, DOF in later stages).

---

## Error Handling

| Failure | Behaviour |
|---------|-----------|
| WebGL not supported | `Engine` constructor throws; `App.tsx` catches and renders a `<FallbackScreen>` with a message |
| Canvas element null | `SceneManager` constructor throws `Error("canvas is null")`; caught in `App.tsx` |
| Shader compile error | NodeMaterial falls back to flat `StandardMaterial`; logged as `console.error` |

---

## Testing Strategy

**Unit tests (Vitest + jsdom)** — `src/scene/` is mostly imperative GPU code; unit tests cover:
- `createIsometricCamera`: asserts `alpha`, `beta`, `lowerBetaLimit`, `upperBetaLimit` are set correctly (mock Scene)
- `createToonMaterial`: asserts a `NodeMaterial` is returned with the expected name (mock Scene)

GPU rendering is not testable in jsdom. Integration / visual tests are deferred to a future Playwright stage.

**Manual smoke test checklist** (run before PR merge):
- [ ] Canvas renders without console errors
- [ ] Ground plane visible at default camera position
- [ ] Pan (middle-mouse drag / two-finger drag) moves the camera
- [ ] Scroll zoom changes radius within bounds
- [ ] Camera rotation is locked (no orbit on left-drag)
- [ ] Window resize resets canvas dimensions without artifacts

---

## Open Questions

None.
