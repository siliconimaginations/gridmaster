import { useEffect, useRef } from 'react'
import { Engine, Scene, ArcRotateCamera, HemisphericLight, MeshBuilder, Vector3 } from '@babylonjs/core'

/**
 * Stage 0 proof-of-concept: Babylon.js canvas loads and renders a test sphere.
 * This will be replaced by the actual grid scene in Stage 3.
 */
export default function App() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const engine = new Engine(canvas, true)
    const scene = new Scene(engine)

    // Camera
    const camera = new ArcRotateCamera('camera', -Math.PI / 2, Math.PI / 3, 10, Vector3.Zero(), scene)
    camera.attachControl(canvas, true)

    // Light
    new HemisphericLight('light', new Vector3(0, 1, 0), scene)

    // Placeholder mesh — removed in Stage 3
    MeshBuilder.CreateSphere('placeholder', { diameter: 2 }, scene)

    engine.runRenderLoop(() => scene.render())
    window.addEventListener('resize', () => engine.resize())

    return () => {
      engine.dispose()
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      style={{ width: '100%', height: '100%', display: 'block' }}
    />
  )
}
