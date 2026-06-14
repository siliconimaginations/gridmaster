import { test, expect } from '@playwright/test'

/**
 * TL — Timeline Strip
 *
 * TL-01: TimelineStrip and the current-time marker are visible after bootstrap
 *
 * @see docs/engineering/15-e2e-ci.md §TL-01
 */

test('TL-01 timeline strip and now-marker are visible after bootstrap', async ({ page }) => {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Timeline renders as part of the main layout regardless of game state
  await expect(page.getByTestId('timeline-strip')).toBeVisible({ timeout: 5_000 })
  await expect(page.getByTestId('timeline-now')).toBeVisible({ timeout: 5_000 })
})
