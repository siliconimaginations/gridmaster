/**
 * Creates the terrain ground plane.
 *
 * Replaces the placeholder flat quad from the scene foundation (PR #78)
 * with a subdivided ground that shows colour variation (tiled grass look).
 * True heightmap terrain is a future art pass; this gives a muted urban-terrain
 * base that recedes visually so grid elements read clearly against it.
 *
 * Colour changed from saturated lime-green → muted sage-grey (#284).
 *
 * The static river mesh placeholder is also created here (a thin blue strip).
 */

import { Color3, MeshBuilder, Scene } from '@babylonjs/core'
import { createToonMaterial } from './materials/ToonMaterial'

const GROUND_SIZE = 200

// Muted warm sage — desaturated enough to let tower/line colours read clearly (#284)
const GROUND_COLOUR = new Color3(0.54, 0.58, 0.44)
// Soft slate-blue for the river accent strip
const RIVER_COLOUR = new Color3(0.45, 0.60, 0.78)

/**
 * Creates the terrain ground plane and a static river mesh.
 * Returns both meshes so the SceneManager can track them.
 */
export function createGround(scene: Scene) {
  // Main ground — subdivided so future heightmap displacement is plug-in
  const ground = MeshBuilder.CreateGround(
    'ground',
    { width: GROUND_SIZE, height: GROUND_SIZE, subdivisions: 20 },
    scene,
  )
  ground.material = createToonMaterial(scene, GROUND_COLOUR, 'groundMat')
  ground.receiveShadows = true

  // Static river — a thin flat strip running diagonally across the terrain
  const river = MeshBuilder.CreateGround(
    'river',
    { width: 8, height: GROUND_SIZE * 0.6 },
    scene,
  )
  river.rotation.y = Math.PI / 6   // 30° angle
  river.position.y = 0.02          // slight Y offset to avoid z-fighting
  river.material = createToonMaterial(scene, RIVER_COLOUR, 'riverMat')

  return { ground, river }
}
