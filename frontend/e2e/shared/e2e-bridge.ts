/**
 * Shared TypeScript type for the `window.__e2e` Playwright test bridge.
 *
 * Extracted from inline `(window as { __e2e: ... })` casts to a single
 * named interface, eliminating duplication and type drift across spec files.
 *
 * The global `Window` augmentation means every spec file that imports this
 * module can use `window.__e2e` directly with full type safety — no cast
 * required.
 *
 * @see src/e2ebridge.ts — the runtime installation in the dev/e2e build
 * @see issue #266
 */

import type { useGameStore } from '../../src/state/useGameStore'
import type { SelectedElementInfo } from '../../src/api/types'

/** Snapshot of the Zustand store returned by `window.__e2e.getStore()`. */
export type GameStoreSnapshot = ReturnType<typeof useGameStore.getState>

/** The test bridge installed on `window.__e2e` in dev / e2e builds. */
export interface E2EBridge {
  /**
   * Returns a live snapshot of the current Zustand store state.
   * Access any store field directly: `window.__e2e.getStore().network`.
   */
  getStore: () => GameStoreSnapshot

  /**
   * Runs `action` inside React's `flushSync` so DOM updates are flushed
   * synchronously before `page.evaluate()` returns.
   */
  executeSync: (action: () => void) => void

  /**
   * Selects a network element and synchronously flushes React.
   * Convenience wrapper over `executeSync` for driving `selectElement`.
   */
  flushSelect: (info: SelectedElementInfo | null) => void

  /**
   * Returns a Promise that resolves when `predicate(state)` is true.
   * Rejects after `timeoutMs` (default 10 000 ms).
   */
  waitFor: (predicate: (s: GameStoreSnapshot) => boolean, timeoutMs?: number) => Promise<void>
}

declare global {
  interface Window {
    /** E2E test bridge — available in dev and `--mode e2e` builds only. */
    __e2e: E2EBridge
  }
}
