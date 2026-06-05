# UX: 3D Scene Visual Spec

**Stage**: 1 | **Status**: Draft

---

## Purpose

Art direction for the Babylon.js isometric 3D scene. Establishes the cartoon style, colour language, shader approach, and how each grid element looks.

---

## Visual style

Township-style cartoon game. Warm daylight palette, hand-painted feel, no photorealism.

| Attribute | Direction |
|-----------|-----------|
| Projection | Isometric (fixed camera angle ~30° pitch, 45° yaw) |
| Navigation | Pan (drag) + pinch/scroll zoom; no camera rotation |
| Shading | Toon shader: cel-shaded diffuse + thick black outline pass |
| Lighting | Single directional sun light (warm white), ambient fill (light blue) |
| Shadows | Soft blob shadows under buildings; no real-time shadow maps on terrain |
| Anti-aliasing | MSAA 4× |

---

## Terrain & world

- **Ground**: tiled grass quads, slight colour variation per tile (hand-painted look)
- **Elevation**: low rolling hills using a heightmap; exaggerated for readability (not realistic)
- **Water**: single river mesh with scrolling UV normal map, toon water shader
- **Roads**: flat quad splines, light grey, dashed centre line
- **Trees**: billboard sprites (2 variants: deciduous, conifer), clustered on hill slopes
- **Sky**: solid gradient (light blue → white at horizon); no skybox geometry

---

## Grid elements

### Generator (thermal / gas / coal)

- Squat cylindrical cooling towers (2–3 per plant), warm grey concrete tint
- Animated smoke particle system from stacks (rate ∝ output MW)
- Small nameplate sign floating above: fuel icon + MW label
- Status glow ring at base: green (online) · amber (warning) · red (fault) · grey (offline)

### Generator (wind)

- Cartoon turbine: white nacelle + 3-blade rotor
- Rotor spin speed ∝ output MW (fully stopped when offline)
- Farm = 3–8 turbines clustered on elevated terrain

### Generator (solar)

- Flat blue panel arrays, slight specular highlight
- Panel tilt angle fixed; no sun-tracking animation needed for MVP
- Farm = grid of panels, scaled to capacity

### Substation / bus

- Low cube building, steel-grey roof, chain-link fence outline
- Overhead gantry structure with busbars (thin cylinders)
- Status ring same as generator

### Transmission line

- Thin cartoon cables strung between lattice pylons
- Pylons: simplified triangular silhouette, light grey
- Line colour indicates loading: white (0–70%) · amber (70–90%) · red (>90%)
- **Animated power flow**: small glowing dot particles travel along the line, speed ∝ MW, direction = flow direction

### City / load

- Building cluster scaled to demand level:
  - < 100 MW: 3–5 small houses (village)
  - 100–500 MW: mix of houses + 2-storey shops (town)
  - > 500 MW: multi-storey blocks + office towers (city)
- Buildings grow/upgrade in real time as demand grows over game-years
- Supply status aura: green = fully supplied, amber = partial, red = curtailed/blackout

### Transformer

- Cylindrical body, grey, with cooling fin geometry
- Sits adjacent to the substation building

---

## Colour language summary

| Colour | Meaning |
|--------|---------|
| Green | Healthy / online / supplied |
| Amber | Warning / near-limit / risk |
| Red | Violation / failure / blackout |
| White | Normal line loading |
| Grey | Offline / decommitted |
| Blue | Info / selected / policy |

---

## Level of detail

Two LOD tiers:

| Zoom level | Detail shown |
|------------|-------------|
| Far (region view) | Icon sprites only; no 3D geometry for individual elements |
| Near (local view) | Full 3D meshes + labels + particles |

LOD transition: cross-fade over 0.3 s.

---

## Open questions

None.
