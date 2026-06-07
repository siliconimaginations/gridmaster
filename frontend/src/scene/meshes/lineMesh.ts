/**
 * Procedural mesh for a transmission line (tube + pylons).
 *
 * Line colour tracks `loadingPercent` per the colour language spec.
 * Pylons are placed at regular intervals along the line.
 *
 * @see docs/engineering/14-scene-meshes.md §Transmission line mesh
 */

import { Color3, MeshBuilder, Scene, Vector3 } from '@babylonjs/core'
import { createToonMaterial } from '../materials/ToonMaterial'
import type { BranchDto } from '../../api/types'

const PYLON_SPACING = 20   // world units between pylons
const TUBE_RADIUS   = 0.08
const PYLON_COLOUR  = new Color3(0.82, 0.84, 0.87)

const LINE_COLOURS = {
  normal:   new Color3(0.976, 0.98, 0.98),   // white
  warning:  new Color3(0.98, 0.75, 0.14),    // amber
  critical: new Color3(0.97, 0.53, 0.44),    // red
  offline:  new Color3(0.42, 0.45, 0.5),     // grey
}

export function lineColour(dto: BranchDto): Color3 {
  if (!dto.connected) return LINE_COLOURS.offline
  if (dto.loadingPercent > 90) return LINE_COLOURS.critical
  if (dto.loadingPercent > 70) return LINE_COLOURS.warning
  return LINE_COLOURS.normal
}

function pylonAt(scene: Scene, id: string, pos: Vector3, index: number) {
  const body = MeshBuilder.CreateBox(`pylon_${id}_${index}`, { width: 0.3, height: 4, depth: 0.3 }, scene)
  body.position = pos.clone()
  body.position.y = 2
  const arm = MeshBuilder.CreateBox(`pylon_arm_${id}_${index}`, { width: 4, height: 0.2, depth: 0.2 }, scene)
  arm.position = pos.clone()
  arm.position.y = 4
  const mat = createToonMaterial(scene, PYLON_COLOUR, `pylon_mat_${id}_${index}`)
  body.material = arm.material = mat
  return [body, arm]
}

/**
 * Creates a transmission line tube + equally-spaced pylons.
 * Returns `{ tube, pylons }`.
 */
export function createLineMesh(scene: Scene, from: Vector3, to: Vector3, dto: BranchDto) {
  const path = [from.clone(), to.clone()]
  const tube = MeshBuilder.CreateTube(`line_${dto.id}`, { path, radius: TUBE_RADIUS, updatable: true }, scene)
  tube.material = createToonMaterial(scene, lineColour(dto), `line_mat_${dto.id}`)

  const length = Vector3.Distance(from, to)
  const count = Math.floor(length / PYLON_SPACING)
  const pylons: ReturnType<typeof MeshBuilder.CreateBox>[] = []
  for (let i = 1; i < count; i++) {
    const t = i / count
    const pos = Vector3.Lerp(from, to, t)
    pylons.push(...pylonAt(scene, dto.id, pos, i))
  }

  return { tube, pylons }
}
