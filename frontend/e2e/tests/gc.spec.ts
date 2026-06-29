import '../shared/e2e-bridge'
import { test, expect } from '@playwright/test'

/**
 * GC — Game Clock
 *
 * GC-01: Tick counter increments over time (clock advancing at ≥1×)
 * GC-02: Pause halts the tick stream; resume restarts it
 *
 * @see docs/engineering/15-e2e-ci.md §GC-01–02
 */

test('GC-01 tick counter increments', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  const tick1 = await page.evaluate(() => window.__e2e.getStore().tickNumber)

  // Poll until the tick counter advances rather than sleeping a fixed duration.
  // A fixed sleep is flaky in CI when the WS is slow to deliver the first tick.
  // Timeout 15 s allows for WS connection latency on cold container starts.
  await page.waitForFunction(
    (initial) => window.__e2e.getStore().tickNumber > initial,
    tick1,
    { timeout: 15_000 },
  )

  const tick2 = await page.evaluate(() => window.__e2e.getStore().tickNumber)

  expect(tick2).toBeGreaterThan(tick1)
  await expect(page.getByTestId('hud-tick-number')).not.toHaveText(String(tick1))
})

test('GC-02 pause stops tick counter', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Pause the clock via HUD button
  // Store initialises at STOPPED; wait for WS to deliver RUNNING before clicking pause.
  // Without this, the HUD sends ResumeClock (not PauseClock) and the backend rejects it.
  await expect(page.getByTestId('hud-clock-state')).toHaveText('RUNNING', { timeout: 10_000 })

  await page.getByTestId('hud-playpause-btn').click()

  await expect(page.getByTestId('hud-clock-state')).toHaveText('PAUSED', { timeout: 5_000 })

  const tickAtPause = await page.evaluate(() => window.__e2e.getStore().tickNumber)

  // Wait 5 s — no ticks should arrive while paused
  await page.waitForTimeout(5_000)

  const tickAfterWait = await page.evaluate(() => window.__e2e.getStore().tickNumber)

  expect(tickAfterWait).toBe(tickAtPause)
})
