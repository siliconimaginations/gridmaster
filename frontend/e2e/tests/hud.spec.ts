import '../shared/e2e-bridge'
import { test, expect } from '@playwright/test'

/**
 * HUD — Top and Bottom HUD behaviour
 *
 * HUD-01: Pill badges (load, health, production cost, clock) show non-empty values after bootstrap
 * HUD-02: Speed change — clicking 10× updates clockSpeedMultiplier in the store
 *
 * The "Price" pill (`pill-price`) was replaced by a "Cost" pill
 * (`pill-production-cost`) in #377 — this spec was not updated at the time,
 * causing HUD-01 to fail on every run since (issue #380) because
 * `pill-price` no longer exists in the DOM.
 *
 * @see docs/engineering/13-hud.md
 * @see docs/engineering/15-e2e-ci.md §HUD-01–02
 * @see issue #377
 * @see issue #380
 */

test('HUD-01 pill badges show values after bootstrap', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Wait for first WS update so pills are populated
  await page.waitForFunction(
    () => (window.__e2e?.getStore().tickNumber ?? 0) > 0,
    { timeout: 15_000 },
  )

  // All four pill badges must be visible with non-empty content
  for (const testId of ['pill-clock', 'pill-load', 'pill-health', 'pill-production-cost']) {
    const el = page.getByTestId(testId)
    await expect(el).toBeVisible({ timeout: 5_000 })
    const text = await el.textContent()
    expect(text?.trim().length).toBeGreaterThan(0)
  }
})

test('HUD-02 clicking 10× speed updates store clockSpeedMultiplier', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Wait for RUNNING state so speed buttons are active
  await expect(page.getByTestId('hud-clock-state')).toHaveText('RUNNING', { timeout: 10_000 })

  await page.getByTestId('btn-speed-10').click()

  // Store must reflect the new multiplier within 5 s
  await page.waitForFunction(
    () => window.__e2e.getStore().clockSpeedMultiplier === 10,
    { timeout: 5_000 },
  )

  // Reset to 1× so we don't leave a modified speed behind
  await page.getByTestId('btn-speed-1').click()
  await page.waitForFunction(
    () => window.__e2e.getStore().clockSpeedMultiplier === 1,
    { timeout: 5_000 },
  )
})
