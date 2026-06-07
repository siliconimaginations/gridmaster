import { useEffect, useRef } from 'react'
import { shallow } from 'zustand/shallow'
import { SceneManager } from './scene/SceneManager'
import { useGameStore } from './state/useGameStore'
import { TopHud } from './ui/TopHud'
import { BottomHud } from './ui/BottomHud'

/**
 * Root component. Renders a full-screen Babylon.js canvas with React HUD
 * overlays floating above it.
 *
 * Layout:
 * ```
 *   <div id="game-root">        ← position: relative, fills 100%
 *     <canvas />                ← absolute, fills entire root
 *     <div id="hud-root">       ← absolute, fills root; pointer-events: none
 *       <TopHud />              ← centred at top
 *       <BottomHud />           ← anchored to bottom
 *     </div>
 *   </div>
 * ```
 *
 * All HUD components read state from the Zustand store directly.
 * See docs/engineering/13-hud.md for the full overlay architecture rationale.
 *
 * Store → scene sync:
 * Two `useGameStore.subscribe` selectors (network + violations) push state
 * changes into SceneManager without going through React render cycles.
 * See docs/engineering/14-scene-meshes.md §Store→Scene sync.
 */
export default function App() {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    const manager = new SceneManager(canvas)
    manager.start()

    // Single subscription for both slices ensures atomic updates: no stale-violations
    // render when a full GameStateUpdate changes network + violations together.
    // Violations-only changes use SceneManager.updateViolations (fast path).
    const unsub = useGameStore.subscribe(
      (state) => ({ network: state.network, violations: state.violations }),
      ({ network, violations }) => manager.updateNetwork(network, violations),
      { equalityFn: shallow, fireImmediately: true },
    )

    return () => {
      unsub()
      manager.dispose()
    }
  }, [])

  return (
    <div
      id="game-root"
      style={{ position: 'relative', width: '100%', height: '100%', overflow: 'hidden' }}
    >
      <canvas
        ref={canvasRef}
        style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
      />
      <div
        id="hud-root"
        style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
      >
        <TopHud />
        <BottomHud />
      </div>
    </div>
  )
}
