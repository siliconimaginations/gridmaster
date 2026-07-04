/**
 * GridCanvas — React wrapper for {@link PixiGridRenderer}.
 *
 * Mounts a full-screen canvas, asynchronously creates the renderer (pixi.js v8
 * requires async init), and subscribes to the Zustand store using the same
 * pattern as the old Babylon.js App.tsx so all HUD components are unaffected.
 *
 * Drop-in replacement for the inline canvas + SceneManager in App.tsx:
 *
 *   <GridCanvas onSelect={info => useGameStore.getState().selectElement(info)} />
 */

import { useEffect, useRef } from 'react'
import { shallow } from 'zustand/shallow'
import { PixiGridRenderer } from '../renderer/PixiGridRenderer'
import { useGameStore } from '../state/useGameStore'
import type { SelectedElementInfo } from '../api/types'

interface GridCanvasProps {
  onSelect: (info: SelectedElementInfo | null) => void
}

export function GridCanvas({ onSelect }: GridCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    let renderer: PixiGridRenderer | null = null
    let disposed = false

    // pixi.js v8 init is async; store subscription starts after renderer is ready
    void PixiGridRenderer.create(canvas, onSelect).then(r => {
      if (disposed) { r.dispose(); return }
      renderer = r

      let initialized = false
      const unsub = useGameStore.subscribe(
        state => ({ network: state.network, violations: state.violations }),
        ({ network, violations }, prev) => {
          if (!initialized || network !== prev.network) {
            initialized = true
            renderer!.updateNetwork(network, violations)
          } else {
            renderer!.updateViolations(violations)
          }
        },
        { equalityFn: shallow, fireImmediately: true },
      )

      // Attach unsub to cleanup
      ;(renderer as PixiGridRenderer & { _unsub?: () => void })._unsub = unsub
    })

    return () => {
      disposed = true
      if (renderer) {
        const r = renderer as PixiGridRenderer & { _unsub?: () => void }
        r._unsub?.()
        r.dispose()
      }
    }
  }, [onSelect])

  return (
    <canvas
      ref={canvasRef}
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
    />
  )
}
