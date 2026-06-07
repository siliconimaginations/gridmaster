import { ArcRotateCamera, Scene, Vector3 } from '@babylonjs/core'

/** Locked yaw — 45° gives the classic isometric diagonal perspective. */
const ISO_ALPHA = -Math.PI / 4

/** Locked pitch — ~54° above the horizon (Math.PI/5 ≈ 36° from zenith). */
const ISO_BETA = Math.PI / 5

const ZOOM_MIN = 5
const ZOOM_MAX = 120
const DEFAULT_RADIUS = 40

/**
 * Creates and attaches an isometric {@link ArcRotateCamera} to the scene.
 *
 * Alpha and beta are locked so the viewing angle stays fixed; players can only
 * pan (middle-mouse / two-finger drag) and zoom (scroll / pinch).
 */
export function createIsometricCamera(scene: Scene, canvas: HTMLCanvasElement): ArcRotateCamera {
  const camera = new ArcRotateCamera('isoCamera', ISO_ALPHA, ISO_BETA, DEFAULT_RADIUS, Vector3.Zero(), scene)

  // Lock both rotation axes — clamps any attempted orbit immediately
  camera.lowerAlphaLimit = ISO_ALPHA
  camera.upperAlphaLimit = ISO_ALPHA
  camera.lowerBetaLimit = ISO_BETA
  camera.upperBetaLimit = ISO_BETA

  // Zoom bounds
  camera.lowerRadiusLimit = ZOOM_MIN
  camera.upperRadiusLimit = ZOOM_MAX

  // Pan on the XZ ground plane; lower value = more sensitive (Babylon default is 1000)
  camera.panningSensibility = 1000
  camera.panningInertia = 0.85
  camera.panningAxis = new Vector3(1, 0, 1)

  camera.attachControl(canvas, /* noPreventDefault */ true)

  return camera
}

// Export constants for unit tests
export { ISO_ALPHA, ISO_BETA, ZOOM_MIN, ZOOM_MAX, DEFAULT_RADIUS }
