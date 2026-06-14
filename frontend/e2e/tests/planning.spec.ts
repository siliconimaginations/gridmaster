import { test, expect } from '@playwright/test'

/**
 * PL — Planning Panel
 *
 * PL-01: "Plan Day" button opens the PlanningPanel
 * PL-02: N-1 tab shows either a violations table or the "no violations" empty state
 *
 * Note on PL-02 tab click: The PlanningPanel uses the same 180 ms slide-up
 * animation as the DispatchPanel. We wait for the animation to finish via the
 * Web Animations API (`el.getAnimations()`) rather than using a fixed
 * `waitForTimeout`, then use `tab.evaluate(el => el.click())` rather than
 * Playwright's `force: true`. Native HTMLElement.click() is a trusted event
 * that always bubbles to React's delegated event root, whereas force:true CDP
 * events can be missed by React's fiber reconciliation when the panel is
 * re-rendering on every game tick.
 *
 * @see docs/engineering/15-e2e-ci.md §PL-01–02
 */

async function bootstrapAndOpenPlanning(page: Parameters<Parameters<typeof test>[1]>[0]['page']) {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  await expect(page.getByTestId('btn-plan-day')).toBeEnabled({ timeout: 10_000 })
  await page.getByTestId('btn-plan-day').click()

  await expect(page.getByTestId('planning-panel')).toBeVisible({ timeout: 5_000 })
}

test('PL-01 Plan Day button opens PlanningPanel', async ({ page }) => {
  await bootstrapAndOpenPlanning(page)
})

test('PL-02 N-1 tab shows violations table or empty state', async ({ page }) => {
  await bootstrapAndOpenPlanning(page)

  // Wait for the 180 ms slide-up animation to finish using the Web Animations API.
  await page.getByTestId('planning-panel').evaluate(el =>
    Promise.all(el.getAnimations().map((a: Animation) => a.finished)),
  )

  // Use element.click() via evaluate rather than Playwright force:true.
  // Native HTMLElement.click() is a trusted event that bubbles to React's
  // delegated event root and reliably fires the onClick handler even while
  // the panel is re-rendering on every game tick.
  // PlanningPanel tabs have no aria-selected attribute, so we assert content directly.
  const n1Tab = page.getByTestId('tab-n1')
  await n1Tab.evaluate((el: HTMLElement) => el.click())

  // Either the violations table or the "no violations" empty state must be present
  const table = page.getByTestId('n1-table')
  const empty = page.getByTestId('n1-empty')
  await expect(table.or(empty)).toBeVisible({ timeout: 10_000 })
})
