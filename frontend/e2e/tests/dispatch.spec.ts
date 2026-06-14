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
 * Note on DP-03 tab click: The DispatchPanel uses a CSS slide-up animation
 * (180 ms). We wait 300 ms for the animation to settle, then use
 * `tab.evaluate(el => el.click())` rather than Playwright's `force: true`.
 * Native HTMLElement.click() is a trusted event that always bubbles to React's
 * delegated event root, whereas force:true CDP events can be missed by React's
 * fiber reconciliation when the panel is re-rendering on every game tick.
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

  // Wait for the 180 ms slide-up animation to settle. During animation the
  // tab button's bounding box is off-screen (translateY(100%)), so clicks
  // at those coordinates miss the element entirely.
  // eslint-disable-next-line playwright/no-wait-for-timeout
  await page.waitForTimeout(300)

  const tab = page.getByTestId('tab-dayahead')
  // Use element.click() via evaluate rather than Playwright force:true.
  // Native HTMLElement.click() is a trusted event that bubbles to React's
  // delegated event root and reliably fires the onClick handler even while
  // the panel is re-rendering on every game tick.
  await tab.evaluate((el: HTMLElement) => el.click())
  // aria-selected is set on DispatchPanel tabs; wait for it to confirm React state update
  await expect(tab).toHaveAttribute('aria-selected', 'true', { timeout: 5_000 })
  await expect(page.getByTestId('uc-grid')).toBeVisible({ timeout: 10_000 })
})
