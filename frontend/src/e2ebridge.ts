import { flushSync } from 'react-dom'
import type { SelectedElementInfo } from './api/types'
import { useGameStore } from './state/useGameStore'

/**
 * Installs the `window.__e2e` test bridge, exposing the Zustand store to
 * Playwright tests without canvas pixel inspection.
 *
 * Only included in dev and `--mode e2e` builds (tree-shaken in production).
 * Installed once from `main.tsx` inside a `MODE !== 'production'` guard.
 *
 * @see docs/engineering/15-e2e-ci.md
 */
export function installE2EBridge(): void {
  (window as Window & { __e2e?: unknown }).__e2e = {
    /** Returns a snapshot of the current Zustand store state. */
    getStore: () => useGameStore.getState(),

    /**
     * Selects a network element and **synchronously** flushes React so the
     * DOM reflects the new state before `page.evaluate()` returns.
     *
     * Background: `getStore().selectElement()` called from `page.evaluate`
     * runs outside React's event system.  React 18 batches such updates and
     * may not flush them before the Playwright assertion runs — especially
     * when Babylon.js's `requestAnimationFrame` loop is competing for the
     * browser scheduler.  `flushSync` forces React to commit the update
     * synchronously, so `inspector-panel` is guaranteed to be in the DOM by
     * the time this function returns.  (IP-01 fix — see PR #239.)
     */
    flushSelect: (info: SelectedElementInfo | null) => {
      flushSync(() => {
        useGameStore.getState().selectElement(info)
      })
    },

    /**
     * Returns a Promise that resolves when `predicate(state)` is true.
     *
     * Uses Zustand's subscribe to react to future updates, then checks
     * immediately in case the condition already holds.
     */
    waitFor: (
      predicate: (s: ReturnType<typeof useGameStore.getState>) => boolean,
      timeoutMs = 10_000,
    ) =>
      new Promise<void>((resolve, reject) => {
        const deadline = Date.now() + timeoutMs
        const unsub = useGameStore.subscribe((state) => {
          if (predicate(state)) {
            unsub()
            resolve()
          } else if (Date.now() > deadline) {
            unsub()
            reject(new Error('waitFor timeout'))
          }
        })
        // Check immediately in case condition already holds
        if (predicate(useGameStore.getState())) {
          unsub()
          resolve()
        }
      }),
  }
}
