/**
 * Procedural mesh for a generator element.
 *
 * Geometry: cylindrical cooling tower + status ring at base.
 *
 * Visual encoding (two layers of information):
 * - **Tower body colour**: encodes fuel type when committed; dark charcoal when
 *   offline; red when in fault — immediately readable at a glance.
 * - **Status ring colour**: encodes fine-grained operational state
 *   (green = producing, amber = committed but zero output, red = fault,
 *   grey = offline).
 *
 * @see docs/engineering/14-scene-meshes.md §Generator mesh
 */

import { Color3, MeshBuilder, Scene, StandardMaterial, Vector3 } from '@babylonjs/core'
import { createToonMaterial } from '../materials/ToonMaterial'
import type { GeneratorDto, ViolationDto } from '../../api/types'

// ── Ring status colours ───────────────────────────────────────────────────────

const STATUS = {
  online:  new Color3(0.29, 0.86, 0.5),   // #4ade80 green
  warning: new Color3(0.98, 0.75, 0.14),  // #fbbf24 amber
  fault:   new Color3(0.97, 0.53, 0.44),  // #f87171 red
  offline: new Color3(0.42, 0.45, 0.5),   // #6b7280 grey
}

export type GeneratorStatus = keyof typeof STATUS

// ── Tower colours by fuel type ────────────────────────────────────────────────

/**
 * Base tower colours for committed generators, keyed by normalised fuel type.
 * Players can identify generator technology at a glance via tower colour when
 * generators are online.
 */
const FUEL_COLOURS: Record<string, Color3> = {
  COAL:    new Color3(0.35, 0.35, 0.38),  // charcoal grey (coal)
  GAS:     new Color3(0.4,  0.6,  0.85),  // steel blue (gas)
  WIND:    new Color3(0.82, 0.92, 0.98),  // pale sky blue (wind)
  SOLAR:   new Color3(0.98, 0.82, 0.10),  // golden yellow (solar)
  NUCLEAR: new Color3(0.6,  0.3,  0.85),  // violet (nuclear)
  HYDRO:   new Color3(0.2,  0.70, 0.80),  // teal (hydro)
  CCGT:    new Color3(0.3,  0.65, 0.90),  // lighter blue (combined-cycle gas)
}
/** Fallback for unrecognised fuel strings. */
const DEFAULT_FUEL_COLOUR = new Color3(0.61, 0.64, 0.686)  // warm grey

/** Status-level tower colour overrides that take precedence over fuel type. */
const TOWER_STATUS_OVERRIDE: Partial<Record<GeneratorStatus, Color3>> = {
  fault:   new Color3(0.90, 0.25, 0.25),  // red — fault condition
  offline: new Color3(0.20, 0.21, 0.23),  // dark charcoal — decommitted
}

// ── Public helpers ────────────────────────────────────────────────────────────

export function generatorStatus(dto: GeneratorDto, violations: readonly ViolationDto[]): GeneratorStatus {
  const hasFault = violations.some((v) => v.elementId === dto.id)
  if (hasFault) return 'fault'
  if (!dto.committed) return 'offline'
  if (dto.activePowerMw === 0) return 'warning'
  return 'online'
}

/**
 * Returns the tower body colour for a generator.
 *
 * Priority: status override (fault/offline) → fuel type → default.
 * Exported for testing; also used inside {@link createGeneratorMesh} and
 * {@link updateGeneratorStatus}.
 */
export function towerColour(fuelType: string, status: GeneratorStatus): Color3 {
  return (
    TOWER_STATUS_OVERRIDE[status] ??
    FUEL_COLOURS[fuelType.toUpperCase()] ??
    DEFAULT_FUEL_COLOUR
  )
}

// ── Mesh creation ─────────────────────────────────────────────────────────────

/**
 * Fuel types that render with a chimney stack on top of their tower (#270).
 * Thermal generators have a distinct industrial silhouette vs. renewables.
 */
const CHIMNEY_FUELS = new Set(['COAL', 'GAS', 'CCGT', 'NUCLEAR'])

/** Dark soot colour for chimney stacks. */
const CHIMNEY_COLOUR = new Color3(0.18, 0.18, 0.20)

/**
 * Creates a generator mesh assembly at `position`.
 *
 * For thermal generators (COAL, GAS, CCGT, NUCLEAR) a narrower chimney
 * cylinder is added on top of the main tower to give a recognisable industrial
 * silhouette (#270).
 *
 * Returns `{ tower, chimney, ring }` where `chimney` is `null` for non-thermal
 * fuel types. The tower and ring can be recoloured on each tick via
 * {@link updateGeneratorStatus} without recreating geometry.
 */
export function createGeneratorMesh(
  scene: Scene,
  position: Vector3,
  dto: GeneratorDto,
  violations: readonly ViolationDto[] = [],
) {
  const status = generatorStatus(dto, violations)

  // Main body — cylindrical tower (height 4, centred at position.y)
  const tower = MeshBuilder.CreateCylinder(`gen_tower_${dto.id}`, { diameter: 3, height: 4 }, scene)
  tower.position = position.clone()
  tower.material = createToonMaterial(scene, towerColour(dto.fuelType, status), `gen_tower_mat_${dto.id}`)

  // Chimney stack — only for thermal fuel types
  // Tower top = position.y + 2; chimney (height 3) centred at position.y + 3.5
  let chimney: ReturnType<typeof MeshBuilder.CreateCylinder> | null = null
  if (CHIMNEY_FUELS.has(dto.fuelType.toUpperCase())) {
    chimney = MeshBuilder.CreateCylinder(`gen_chimney_${dto.id}`, { diameter: 1.0, height: 3 }, scene)
    chimney.position = new Vector3(position.x, position.y + 3.5, position.z)
    chimney.material = createToonMaterial(scene, CHIMNEY_COLOUR, `gen_chimney_mat_${dto.id}`)
  }

  // Status ring at base
  const ring = MeshBuilder.CreateTorus(`gen_ring_${dto.id}`, { diameter: 3.6, thickness: 0.2 }, scene)
  ring.position = position.clone()
  ring.material = createToonMaterial(scene, STATUS[status], `gen_ring_mat_${dto.id}`)

  return { tower, chimney, ring }
}

// ── Status update ─────────────────────────────────────────────────────────────

/**
 * Updates both the tower body colour and the status ring colour.
 *
 * Called on every game tick for existing meshes to keep the scene in sync with
 * the generator's operational state without recreating geometry.
 *
 * @param meshes  The `{ tower, chimney, ring }` returned by {@link createGeneratorMesh}.
 * @param fuelType The generator's fuel type string (e.g. `"GAS"`, `"COAL"`).
 * @param status  The derived {@link GeneratorStatus} for this tick.
 */
export function updateGeneratorStatus(
  meshes: ReturnType<typeof createGeneratorMesh>,
  fuelType: string,
  status: GeneratorStatus,
): void {
  if (meshes.tower.material) {
    (meshes.tower.material as StandardMaterial).diffuseColor = towerColour(fuelType, status)
  }
  if (meshes.ring.material) {
    (meshes.ring.material as StandardMaterial).diffuseColor = STATUS[status]
  }
  // Chimney colour is static — no update needed
}
