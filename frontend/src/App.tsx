import { useEffect, useRef, useState } from 'react'
import { shallow } from 'zustand/shallow'
import { SceneManager } from './scene/SceneManager'
import { useGameStore } from './state/useGameStore'
import { TopHud } from './ui/TopHud'
import { BottomHud } from './ui/BottomHud'
import { BootstrapOverlay } from './ui/BootstrapOverlay'
import { useSessionBootstrap } from './state/sessionBootstrap'
import { AlertToastContainer } from './ui/AlertToast'
import { InspectorPanel } from './ui/InspectorPanel'
import { DispatchPanel } from './ui/DispatchPanel'
import { PlanningPanel } from './ui/PlanningPanel'
import { TimelineStrip } from './ui/TimelineStrip'
import { EventCardPanel } from './ui/EventCardPanel'
import { GameOverPanel } from './ui/GameOverPanel'
import { GridCanvas } from './components/GridCanvas'

/**
 * Feature flag: set `VITE_USE_PIXI=true` in `.env.local` (or CI env) to
 * activate the PixiJS renderer. When true, renders `<GridCanvas>` which
 * manages its own async pixi.js init and store subscription. When false,
 * the original Babylon.js + SceneManager path is used unchanged.
 *
 * See docs/engineering/15-pixi-renderer.md §Migration plan.
 */
const USE_PIXI = import.meta.env.VITE_USE_PIXI === 'true'

/**
 * Root component. Renders a full-screen canvas (Babylon.js or PixiJS depending
 * on {@link USE_PIXI}) with React HUD overlays floating above it.
 *
 * Layout:
 * ```
 *   <div id="game-root">        ← position: relative, fills 100%
 *     <canvas /> | <GridCanvas> ← absolute, fills entire root
 *     <div id="hud-root">       ← absolute, fills root; pointer-events: none
 *       <TopHud />              ← centred at top
 *       <BottomHud />           ← anchored to bottom
 *     </div>
 *   </div>
 * ```
 *
 * All HUD components read state from the Zustand store directly.
 * See docs/engineering/13-hud.md for the full overlay architecture rationale.
 */
export default function App() {
  // canvasRef is only used in the Babylon path; stays null in PixiJS mode (harmless)
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const { status: bootstrapStatus, error: bootstrapError, retry: retryBootstrap } = useSessionBootstrap()
  const [dispatchPanelOpen, setDispatchPanelOpen] = useState(false)
  const [planningPanelOpen, setPlanningPanelOpen] = useState(false)

  useEffect(() => {
    // GridCanvas handles its own lifecycle when PixiJS mode is active
    if (USE_PIXI) return

    const canvas = canvasRef.current
    if (!canvas) return

    const manager = new SceneManager(canvas, (info) => useGameStore.getState().selectElement(info))
    manager.start()

    // Single subscription for both slices ensures atomic updates: no stale-violations
    // render when a full GameStateUpdate changes network + violations together.
    // Routes to updateNetwork on network change, updateViolations (fast path) otherwise.
    let initialized = false
    const unsub = useGameStore.subscribe(
      (state) => ({ network: state.network, violations: state.violations }),
      ({ network, violations }, prev) => {
        if (!initialized || network !== prev.network) {
          // Full update: network changed, or initial fire (fireImmediately with current===prev)
          initialized = true
          manager.updateNetwork(network, violations)
        } else {
          // Violations-only change — fast path skips full mesh loop
          manager.updateViolations(violations)
        }
      },
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
      {USE_PIXI
        ? <GridCanvas onSelect={(info) => useGameStore.getState().selectElement(info)} />
        : <canvas
            ref={canvasRef}
            style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
          />
      }
      <div
        id="hud-root"
        style={{ position: 'absolute', inset: 0, pointerEvents: 'none' }}
      >
        <TopHud />
        <TimelineStrip />
        <BottomHud onOpenDispatch={() => setDispatchPanelOpen(true)} onOpenPlanning={() => setPlanningPanelOpen(true)} />
        <AlertToastContainer />
        <InspectorPanel />
        <EventCardPanel />
        <DispatchPanel open={dispatchPanelOpen} onClose={() => setDispatchPanelOpen(false)} />
        <PlanningPanel open={planningPanelOpen} onClose={() => setPlanningPanelOpen(false)} />
      </div>
      <BootstrapOverlay status={bootstrapStatus} error={bootstrapError} onRetry={retryBootstrap} />
      <GameOverPanel />
    </div>
  )
}

