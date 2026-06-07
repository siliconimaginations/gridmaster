/**
 * Procedural mesh for a substation / bus node.
 *
 * Geometry: squat box building + status ring.
 * Status is derived from voltage violations on buses in this substation.
 *
 * @see docs/engineering/14-scene-meshes.md §Substation mesh
 */

import { Color3, MeshBuilder, Scene, Vector3 } from '@babylonjs/core'
import { createToonMaterial } from '../materials/ToonMaterial'
import type { ViolationDto } from '../../api/types'

const BUILDING_COLOUR = new Color3(0.82, 0.84, 0.87)  // steel grey
const STATUS_COLOURS = {
  ok:      new Color3(0.29, 0.86, 0.5),
  warning: new Color3(0.98, 0.75, 0.14),
  fault:   new Color3(0.97, 0.53, 0.44),
}

export type SubstationStatus = keyof typeof STATUS_COLOURS

export function substationStatus(violations: readonly ViolationDto[]): SubstationStatus {
  if (violations.some((v) => v.value > v.limit * 1.1)) return 'fault'
  if (violations.length > 0) return 'warning'
  return 'ok'
}

/**
 * Creates a substation mesh at `position`.
 * Returns `{ building, ring }`.
 */
export function createSubstationMesh(scene: Scene, position: Vector3, substationId: string, violations: readonly ViolationDto[] = []) {
  const building = MeshBuilder.CreateBox(`sub_bld_${substationId}`, { width: 4, height: 2, depth: 4 }, scene)
  building.position = position.clone()
  building.position.y = 1

  const ring = MeshBuilder.CreateTorus(`sub_ring_${substationId}`, { diameter: 5, thickness: 0.2 }, scene)
  ring.position = position.clone()

  building.material = createToonMaterial(scene, BUILDING_COLOUR, `sub_mat_${substationId}`)

  const status = substationStatus(violations)
  ring.material = createToonMaterial(scene, STATUS_COLOURS[status], `sub_ring_mat_${substationId}`)

  return { building, ring }
}
