import { Engine, Scene } from '@babylonjs/core'
import type { GridNetworkDto, ViolationDto } from '../api/types'
import { createIsometricCamera } from './camera'
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

  private readonly meshRegistry: MeshRegistry
  private currentNetwork: GridNetworkDto | null = null
  private currentViolations: readonly ViolationDto[] = []

  constructor(canvas: HTMLCanvasElement) {
    if (!canvas) throw new Error('SceneManager: canvas element is null')

    // MSAA 4× anti-aliasing via the Engine antialias flag
    this.engine = new Engine(canvas, /* antialias */ true, {
      preserveDrawingBuffer: false,
      stencil: true, // required for some post-process effects
    })

    this.scene = new Scene(this.engine)

    // Wire up sub-systems as specified in the design doc
    createIsometricCamera(this.scene, canvas)
    createSceneLighting(this.scene)
    createGround(this.scene)

    this.meshRegistry = new MeshRegistry(this.scene)

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
    this.currentNetwork = network
    this.currentViolations = violations
    this.meshRegistry.updateNetwork(network, violations)
  }

  /**
   * Push updated violations to the scene without a full network refresh.
   * Re-renders status rings for generators and substations.
   */
  updateViolations(violations: readonly ViolationDto[]): void {
    this.currentViolations = violations
    this.meshRegistry.updateNetwork(this.currentNetwork, violations)
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
