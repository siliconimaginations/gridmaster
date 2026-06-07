import { Engine, Scene } from '@babylonjs/core'
import { createIsometricCamera } from './camera'
import { createGround } from './ground'
import { createSceneLighting } from './lighting'

/**
 * Owns and manages the Babylon.js engine and scene lifecycle.
 *
 * `SceneManager` is a plain class (not React state) to avoid:
 * - Unnecessary re-renders when the engine internal state mutates
 * - Double-initialisation under React StrictMode (which mounts components twice
 *   in development to expose side-effect bugs)
 *
 * Usage in React:
 * ```tsx
 * const managerRef = useRef<SceneManager | null>(null)
 * useEffect(() => {
 *   if (!canvasRef.current) return
 *   managerRef.current = new SceneManager(canvasRef.current)
 *   managerRef.current.start()
 *   return () => { managerRef.current?.dispose() }
 * }, [])
 * ```
 */
export class SceneManager {
  readonly engine: Engine
  readonly scene: Scene

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

    // Resize handler — keeps canvas pixel dimensions in sync with CSS layout
    window.addEventListener('resize', this._onResize)
  }

  /** Start the Babylon render loop. */
  start(): void {
    this.engine.runRenderLoop(() => {
      this.scene.render()
    })
  }

  /** Stop the render loop and release all GPU resources. */
  dispose(): void {
    window.removeEventListener('resize', this._onResize)
    this.scene.dispose()
    this.engine.dispose()
  }

  private readonly _onResize = () => {
    this.engine.resize()
  }
}
