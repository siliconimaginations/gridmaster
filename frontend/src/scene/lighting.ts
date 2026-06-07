import { Color3, DirectionalLight, HemisphericLight, Scene, Vector3 } from '@babylonjs/core'

/**
 * Adds scene lighting matching the UX visual spec:
 * - A warm directional sun light from upper-left
 * - A cool ambient fill (HemisphericLight) to soften shadows
 *
 * Both lights are returned so callers can adjust intensity at runtime
 * (e.g. day/night transitions in future game modes).
 */
export function createSceneLighting(scene: Scene): {
  sun: DirectionalLight
  ambient: HemisphericLight
} {
  // Warm directional sun — upper-left to cast readable shadows
  const sun = new DirectionalLight('sun', new Vector3(-1, -2, -1).normalize(), scene)
  sun.diffuse = new Color3(1.0, 0.97, 0.88) // warm white
  sun.specular = new Color3(0.3, 0.3, 0.3)
  sun.intensity = 1.2

  // Cool ambient fill — light blue sky bounce
  const ambient = new HemisphericLight('ambient', new Vector3(0, 1, 0), scene)
  ambient.diffuse = new Color3(0.72, 0.82, 1.0) // sky blue
  ambient.groundColor = new Color3(0.4, 0.35, 0.3) // warm ground bounce
  ambient.intensity = 0.5

  return { sun, ambient }
}
