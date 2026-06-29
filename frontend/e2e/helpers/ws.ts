import '../shared/e2e-bridge'
import type { Page } from '@playwright/test'

/**
 * Waits until `__e2e.waitFor` resolves, i.e. until the inlined predicate
 * returns true against the Zustand store.
 *
 * Use `page.evaluate` (not `page.waitForFunction`) because `__e2e.waitFor`
 * returns a Promise — `page.waitForFunction` expects a synchronous boolean
 * and will always time out if given a Promise.
 *
 * NOTE: Playwright's `page.evaluate(fn, arg)` requires `arg` to be
 * JSON-serializable; functions cannot be passed as arguments. Predicates
 * must therefore be inlined in the `pageFunction` string, not passed as `arg`.
 *
 * Patterns:
 * ```ts
 * // Async: use page.evaluate (awaits Promise returned by __e2e.waitFor)
 * await page.evaluate(() =>
 *   window.__e2e.waitFor((s: any) => s.clockState === 'PAUSED')
 * )
 *
 * // Sync: use page.waitForFunction (polls synchronous boolean efficiently)
 * await page.waitForFunction(
 *   () => window.__e2e?.getStore().tickNumber > 0,
 *   { timeout: 10_000 },
 * )
 * ```
 *
 * @see docs/engineering/15-e2e-ci.md §Test Helpers
 */

/** Waits until clock state matches the expected value. */
export const waitForClockState = (page: Page, state: string): Promise<void> =>
  page.evaluate(
    (s) => window.__e2e.waitFor((store) => store.clockState === s),
    state,
  )

/** Waits until tickNumber has advanced past `baseline`. */
export const waitForTick = (page: Page, baseline: number): Promise<unknown> =>
  page.waitForFunction(
    (n) => (window.__e2e?.getStore().tickNumber ?? 0) > n,
    baseline,
    { timeout: 10_000 },
  )
