import { Color3, MeshBuilder, Scene } from '@babylonjs/core'
import { createToonMaterial } from './materials/ToonMaterial'

/** Side length of the ground plane in world units. Sized to cover the 14-bus network layout. */
const GROUND_SIZE = 200

/**
 * Creates a placeholder flat ground plane.
 *
 * This will be replaced by procedural terrain with hills and a river in issue #79.
 * It exists here so the camera and lights have something to render against, making
 * the manual smoke-test checklist runnable.
 */
export function createGround(scene: Scene) {
  const ground = MeshBuilder.CreateGround('ground', { width: GROUND_SIZE, height: GROUND_SIZE }, scene)

  const mat = createToonMaterial(scene, new Color3(0.52, 0.72, 0.38), 'groundMat') // grass green
  ground.material = mat

  ground.receiveShadows = true
  // Shadow casting is not a mesh property — exclusion from ShadowGenerators is the correct approach (no generator yet)

  return ground
}
