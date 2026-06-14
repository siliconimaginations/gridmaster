import { test, expect } from '@playwright/test'

/**
 * DP — Dispatch Panel
 *
 * DP-01: "Run Dispatch" button opens the DispatchPanel
 * DP-02: Real-time tab shows at least one generator row
 * DP-03: Day-ahead tab renders the UC schedule grid
 *
 * Each test opens the panel independently; GC-02-style bootstrap wait ensures
 * the network is ready (button only enables once network !== null).
 *
 * @see docs/engineering/15-e2e-ci.md §DP-01–03
 */

async function bootstrapAndOpenDispatch(page: Parameters<Parameters<typeof test>[1]>[0]['page']) {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // btn-dispatch is disabled until network is non-null — wait for it
  await expect(page.getByTestId('btn-dispatch')).toBeEnabled({ timeout: 15_000 })
  await page.getByTestId('btn-dispatch').click()

  // Panel must appear
  await expect(page.getByTestId('dispatch-panel')).toBeVisible({ timeout: 5_000 })
}

test('DP-01 Run Dispatch button opens DispatchPanel', async ({ page }) => {
  await bootstrapAndOpenDispatch(page)
})

test('DP-02 real-time tab shows at least one generator row', async ({ page }) => {
  await bootstrapAndOpenDispatch(page)

  // Real-time tab is default; generator rows are keyed as dispatch-row-{id}
  await expect(page.locator('[data-testid^="dispatch-row-"]').first()).toBeVisible({ timeout: 5_000 })
})

test('DP-03 day-ahead tab renders UC schedule grid', async ({ page }) => {
  await bootstrapAndOpenDispatch(page)

  const tab = page.getByTestId('tab-dayahead')
  await tab.click()
  // Wait for the tab to become selected before asserting grid contents
  await expect(tab).toHaveAttribute('aria-selected', 'true', { timeout: 5_000 })
  await expect(page.getByTestId('uc-grid')).toBeVisible({ timeout: 10_000 })
})
