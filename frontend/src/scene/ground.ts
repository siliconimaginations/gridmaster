/**
 * Creates the terrain ground plane.
 *
 * Replaces the placeholder flat quad from the scene foundation (PR #78)
 * with a subdivided ground that shows colour variation (tiled grass look).
 * True heightmap terrain is a future art pass; this gives a readable green
 * base with minor visual interest at low cost.
 *
 * The static river mesh placeholder is also created here (a thin blue strip).
 */

import { Color3, MeshBuilder, Scene } from '@babylonjs/core'
import { createToonMaterial } from './materials/ToonMaterial'

const GROUND_SIZE = 200

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
  ground.material = createToonMaterial(scene, new Color3(0.52, 0.72, 0.38), 'groundMat')
  ground.receiveShadows = true

  // Static river — a thin flat strip running diagonally across the terrain
  const river = MeshBuilder.CreateGround(
    'river',
    { width: 8, height: GROUND_SIZE * 0.6 },
    scene,
  )
  river.rotation.y = Math.PI / 6   // 30° angle
  river.position.y = 0.02          // slight Y offset to avoid z-fighting
  river.material = createToonMaterial(scene, new Color3(0.37, 0.62, 0.89), 'riverMat')

  return { ground, river }
}
