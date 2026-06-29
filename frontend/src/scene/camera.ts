import { ArcRotateCamera, Scene, Vector3 } from '@babylonjs/core'
import type { GridNetworkDto } from '../api/types'
import { layoutBuses } from './layout/busLayout'

/** Locked yaw — 45° gives the classic isometric diagonal perspective. */
const ISO_ALPHA = -Math.PI / 4

/** Locked pitch — ~54° above the horizon (Math.PI/5 ≈ 36° from zenith). */
const ISO_BETA = Math.PI / 5

const ZOOM_MIN = 5
const ZOOM_MAX = 120
const DEFAULT_RADIUS = 40

/** Padding fraction beyond the network AABB before the panning hard stop. */
const PAN_PADDING = 0.25

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

/**
 * Recentres the camera on the network's spatial centroid and sets panning
 * distance limits so every node is reachable. (#268)
 *
 * Safe to call whenever the network first loads — subsequent calls with the
 * same network are cheap (no visible camera jump if target is already close).
 */
export function updateCameraForNetwork(camera: ArcRotateCamera, network: GridNetworkDto): void {
  if (network.buses.length === 0) return

  const positions = layoutBuses(network.buses)
  const xs = [...positions.values()].map((p) => p.x)
  const zs = [...positions.values()].map((p) => p.z)

  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minZ = Math.min(...zs)
  const maxZ = Math.max(...zs)

  const cx = (minX + maxX) / 2
  const cz = (minZ + maxZ) / 2

  // Diagonal half-extent of the AABB
  const halfExtent = Math.sqrt(((maxX - minX) / 2) ** 2 + ((maxZ - minZ) / 2) ** 2)
  const panLimit = halfExtent * (1 + PAN_PADDING)

  camera.setTarget(new Vector3(cx, 0, cz))
  camera.panningDistanceLimit = panLimit
}

// Export constants for unit tests
export { ISO_ALPHA, ISO_BETA, ZOOM_MIN, ZOOM_MAX, DEFAULT_RADIUS }
