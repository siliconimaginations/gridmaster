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

/**
 * Serialises every PixiGridRenderer create/dispose across effect runs.
 *
 * `PIXI.Application.init()` is async and cannot run twice concurrently on the
 * same canvas: React StrictMode double-mounts the component in dev, and the
 * second mount's init would start while the first one (or its dispose) is
 * still in flight. On some machines that race deadlocks WebGL setup and
 * freezes the main thread — the "stuck on Connecting to the grid…" bug (#342).
 * Chaining all lifecycle steps onto one promise guarantees strict
 * create → dispose → create ordering regardless of mount timing.
 *
 * The chain lives on `globalThis` (not module scope) so it survives Vite HMR:
 * an HMR update replaces the module — a module-level variable would reset and
 * let the old module's dispose overlap the new module's create, recreating
 * exactly the race this exists to prevent.
 */
const lifecycleHost = globalThis as { __gridCanvasLifecycle?: Promise<void> }

/** Appends a lifecycle step to the global chain and returns the new tail. */
function chainLifecycle(step: () => Promise<void> | void): Promise<void> {
  const next = (lifecycleHost.__gridCanvasLifecycle ?? Promise.resolve()).then(step)
  // Keep the chain alive even if a step throws — a broken chain would block
  // every future mount.
  lifecycleHost.__gridCanvasLifecycle = next.catch(() => undefined)
  return next
}

export function GridCanvas({ onSelect }: GridCanvasProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)

  // Keep the latest onSelect in a ref so the lifecycle effect never re-runs
  // because of an inline callback prop (App.tsx passes a new identity every
  // render, which previously re-created the whole renderer each time).
  const onSelectRef = useRef(onSelect)
  useEffect(() => {
    onSelectRef.current = onSelect
  }, [onSelect])

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return

    let renderer: PixiGridRenderer | null = null
    let unsub: (() => void) | null = null
    let disposed = false

    // pixi.js v8 init is async; store subscription starts after renderer is ready
    void chainLifecycle(async () => {
      if (disposed) return
      const r = await PixiGridRenderer.create(canvas, (info) => onSelectRef.current(info))
      if (disposed) {
        r.dispose()
        return
      }
      renderer = r

      let initialized = false
      unsub = useGameStore.subscribe(
        state => ({ network: state.network, violations: state.violations }),
        ({ network, violations }, prev) => {
          if (!initialized || network !== prev.network) {
            initialized = true
            r.updateNetwork(network, violations)
          } else {
            r.updateViolations(violations)
          }
        },
        { equalityFn: shallow, fireImmediately: true },
      )
    })

    return () => {
      disposed = true
      // Chain the teardown so it cannot overlap a pending create.
      void chainLifecycle(() => {
        unsub?.()
        unsub = null
        renderer?.dispose()
        renderer = null
      })
    }
  }, [])

  return (
    <canvas
      ref={canvasRef}
      style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'block' }}
    />
  )
}
