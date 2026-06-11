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
  ;(window as Window & { __e2e?: unknown }).__e2e = {
    /** Returns a snapshot of the current Zustand store state. */
    getStore: () => useGameStore.getState(),

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
