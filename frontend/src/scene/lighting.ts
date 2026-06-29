import { Color3, DirectionalLight, HemisphericLight, Scene, Vector3 } from '@babylonjs/core'

/**
 * Adds scene lighting and atmosphere matching the UX visual spec (#270):
 * - A warm directional sun light from upper-left
 * - A cool hemisphere ambient fill to soften shadows
 * - Exponential-squared fog for atmospheric depth at range
 *
 * Both lights are returned so callers can adjust intensity at runtime
 * (e.g. day/night transitions in future game modes).
 */
export function createSceneLighting(scene: Scene): {
  sun: DirectionalLight
  ambient: HemisphericLight
} {
  // Warm directional sun — angled to cast legible isometric shadows
  const sun = new DirectionalLight('sun', new Vector3(-1, -2.5, -1).normalize(), scene)
  sun.diffuse = new Color3(1.0, 0.96, 0.84)    // warm golden-white
  sun.specular = new Color3(0.15, 0.15, 0.12)  // very soft specular
  sun.intensity = 1.4

  // Sky hemisphere — top is sky-blue, bottom is warm terrain bounce
  const ambient = new HemisphericLight('ambient', new Vector3(0, 1, 0), scene)
  ambient.diffuse = new Color3(0.65, 0.80, 1.0)       // sky blue
  ambient.groundColor = new Color3(0.52, 0.48, 0.38)  // warm sandy ground bounce
  ambient.specular = new Color3(0, 0, 0)               // no specular from hemisphere
  ambient.intensity = 0.55

  // Atmospheric fog — fades distant terrain to sky colour, adds depth (#270)
  scene.fogMode = Scene.FOGMODE_EXP2
  scene.fogDensity = 0.006
  scene.fogColor = new Color3(0.68, 0.82, 0.90)  // matches clearColor blue-grey

  return { sun, ambient }
}
