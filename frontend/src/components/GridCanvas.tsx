/**
 * GridCanvas — React wrapper for {@link PixiGridRenderer}.
 *
 * Mounts a full-screen canvas, creates the renderer, and subscribes to the
 * Zustand store using the same pattern as the old Babylon.js App.tsx so the
 * store and all HUD components are unaffected.
 *
 * Usage (drop-in for the inline canvas + SceneManager in App.tsx):
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

    const renderer = new PixiGridRenderer(canvas, onSelect)

    let initialized = false
    const unsub = useGameStore.subscribe(
      state => ({ network: state.network, violations: state.violations }),
      ({ network, violations }, prev) => {
        if (!initialized || network !== prev.network) {
          initialized = true
          renderer.updateNetwork(network, violations)
        } else {
          renderer.updateViolations(violations)
        }
      },
      { equalityFn: shallow, fireImmediately: true },
    )

    return () => {
      unsub()
      renderer.dispose()
    }
  }, [onSelect])

  return (
    <canvas
      ref={canvasRef}
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
    />
  )
}
