import { ArcRotateCamera, Engine, PointerEventTypes, Scene } from '@babylonjs/core'
import type { GridNetworkDto, SelectedElementInfo, ViolationDto } from '../api/types'
import { createIsometricCamera, updateCameraForNetwork } from './camera'
import { createGround } from './ground'
import { createSceneLighting } from './lighting'
import { MeshRegistry } from './meshes/MeshRegistry'

/**
 * Owns and manages the Babylon.js engine and scene lifecycle.
 *
 * `SceneManager` is a plain class (not React state) to avoid:
 * - Unnecessary re-renders when the engine internal state mutates
 * - Double-initialisation under React StrictMode (which mounts components twice
 *   in development to expose side-effect bugs)
 *
 * Scene updates flow in via {@link updateNetwork} and {@link updateViolations},
 * which delegate to the {@link MeshRegistry}. React wires these in `App.tsx`
 * via Zustand store subscriptions (see docs/engineering/14-scene-meshes.md §Store→Scene sync).
 *
 * Usage in React:
 * ```tsx
 * const manager = new SceneManager(canvas)
 * manager.start()
 * // subscribe store slices (see App.tsx)
 * return () => manager.dispose()
 * ```
 */
export class SceneManager {
  readonly engine: Engine
  readonly scene: Scene

  private readonly camera: ArcRotateCamera
  private readonly meshRegistry: MeshRegistry
  private currentViolations: readonly ViolationDto[] = []
  private cameraCentred = false

  constructor(
    canvas: HTMLCanvasElement,
    private readonly onElementSelected?: (info: SelectedElementInfo | null) => void,
  ) {
    if (!canvas) throw new Error('SceneManager: canvas element is null')

    // MSAA 4× anti-aliasing via the Engine antialias flag
    this.engine = new Engine(canvas, /* antialias */ true, {
      preserveDrawingBuffer: false,
      stencil: true, // required for some post-process effects
    })

    this.scene = new Scene(this.engine)

    // Wire up sub-systems as specified in the design doc
    this.camera = createIsometricCamera(this.scene, canvas)
    createSceneLighting(this.scene)
    createGround(this.scene)

    this.meshRegistry = new MeshRegistry(this.scene)

    // Click picking — resolve the clicked mesh's metadata and fire the selection callback
    this.scene.onPointerObservable.add((pi) => {
      if (pi.type !== PointerEventTypes.POINTERUP) return
      const hit = this.scene.pick(this.scene.pointerX, this.scene.pointerY)
      const meta = hit?.pickedMesh?.metadata as SelectedElementInfo | null | undefined
      this.onElementSelected?.(meta ?? null)
    })

    // Resize handler — keeps canvas pixel dimensions in sync with CSS layout
    window.addEventListener('resize', this._onResize)
  }

  /** Start the Babylon render loop. */
  start(): void {
    this.engine.runRenderLoop(() => {
      this.scene.render()
    })
  }

  /**
   * Push a new network snapshot to the scene.
   * Uses cached violations when none are supplied (e.g. on first network arrival).
   */
  updateNetwork(network: GridNetworkDto | null, violations = this.currentViolations): void {
    // Clear violations when the network is cleared — no network means no violations
    const finalViolations = network ? violations : []
    this.currentViolations = finalViolations
    this.meshRegistry.updateNetwork(network, finalViolations)

    // Centre the camera on the first network snapshot so every node is reachable (#268).
    // Only runs once — subsequent updates leave the camera where the player positioned it.
    if (network && !this.cameraCentred) {
      this.cameraCentred = true
      updateCameraForNetwork(this.camera, network)
    }
  }

  /**
   * Push updated violations to the scene without a full network refresh.
   * Re-renders status rings for generators and substations.
   */
  updateViolations(violations: readonly ViolationDto[]): void {
    this.currentViolations = violations
    // Efficient fast path: only re-colours status rings, skips full mesh loop
    // TODO: #129 add dedicated MeshRegistry.updateViolations() to only re-run status-ring logic — now implemented
    this.meshRegistry.updateViolations(violations)
  }

  /** Stop the render loop and release all GPU resources. */
  dispose(): void {
    window.removeEventListener('resize', this._onResize)
    this.meshRegistry.disposeAll()
    this.scene.dispose()
    this.engine.dispose()
  }

  private readonly _onResize = () => {
    this.engine.resize()
  }
}
