import { useEffect, useRef } from 'react'
import { SceneManager } from './scene/SceneManager'

/**
 * Root component. Renders a full-screen Babylon.js canvas and owns the
 * {@link SceneManager} lifecycle.
 *
 * All game rendering lives in `src/scene/`. This component is intentionally
 * thin — it hands the canvas element to SceneManager and tears it down on
 * unmount.
 */
export default function App() {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const managerRef = useRef<SceneManager | null>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const manager = new SceneManager(canvas)
    managerRef.current = manager
    manager.start()

    return () => {
      manager.dispose()
      managerRef.current = null
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      style={{ width: '100%', height: '100%', display: 'block' }}
    />
  )
}
