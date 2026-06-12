/**
 * Procedural mesh for a generator element.
 *
 * Geometry: cylindrical cooling tower + status ring at base.
 * Status ring colour encodes generator state (committed/active/fault).
 *
 * @see docs/engineering/14-scene-meshes.md §Generator mesh
 */

import { Color3, MeshBuilder, Scene, StandardMaterial, Vector3 } from '@babylonjs/core'
import { createToonMaterial } from '../materials/ToonMaterial'
import type { GeneratorDto, ViolationDto } from '../../api/types'

const TOWER_COLOUR = new Color3(0.61, 0.64, 0.686)  // warm grey
const STATUS = {
  online:  new Color3(0.29, 0.86, 0.5),   // #4ade80 green
  warning: new Color3(0.98, 0.75, 0.14),  // #fbbf24 amber
  fault:   new Color3(0.97, 0.53, 0.44),  // #f87171 red
  offline: new Color3(0.42, 0.45, 0.5),   // #6b7280 grey
}

export type GeneratorStatus = keyof typeof STATUS

export function generatorStatus(dto: GeneratorDto, violations: readonly ViolationDto[]): GeneratorStatus {
  const hasFault = violations.some((v) => v.elementId === dto.id)
  if (hasFault) return 'fault'
  if (!dto.committed) return 'offline'
  if (dto.activePowerMw === 0) return 'warning'
  return 'online'
}

/**
 * Creates a generator mesh assembly at `position`.
 * Returns `[tower, statusRing]` so the ring material can be updated separately.
 */
export function createGeneratorMesh(scene: Scene, position: Vector3, dto: GeneratorDto, violations: readonly ViolationDto[] = []) {
  const tower = MeshBuilder.CreateCylinder(`gen_tower_${dto.id}`, { diameter: 3, height: 4 }, scene)
  tower.position = position.clone()

  const ring = MeshBuilder.CreateTorus(`gen_ring_${dto.id}`, { diameter: 3.6, thickness: 0.2 }, scene)
  ring.position = position.clone()

  const towerMat = createToonMaterial(scene, TOWER_COLOUR, `gen_tower_mat_${dto.id}`)
  tower.material = towerMat

  const status = generatorStatus(dto, violations)
  const ringMat = createToonMaterial(scene, STATUS[status], `gen_ring_mat_${dto.id}`)
  ring.material = ringMat

  return { tower, ring }
}

/** Updates only the status ring colour without recreating the mesh. */
export function updateGeneratorStatus(ring: ReturnType<typeof createGeneratorMesh>['ring'], status: GeneratorStatus): void {
  if (ring.material) {
    ;(ring.material as StandardMaterial).diffuseColor = STATUS[status]
  }
}
