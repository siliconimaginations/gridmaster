import { test, expect } from '@playwright/test'

/**
 * PL — Planning Panel
 *
 * PL-01: "Plan Day" button opens the PlanningPanel
 * PL-02: N-1 tab shows either a violations table or the "no violations" empty state
 *
 * Note on PL-02 tab click: The PlanningPanel uses the same 180 ms slide-up
 * animation as the DispatchPanel. We wait 300 ms for the animation to settle
 * before clicking the N-1 tab. `force: true` bypasses the stability check,
 * which never resolves because the panel content re-renders on every game tick.
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

  // Wait for the 180 ms slide-up animation to settle before clicking the tab.
  // During animation the tab button's bounding box is off-screen (translateY(100%)),
  // so a click at those coordinates does not reach the React onClick handler.
  // eslint-disable-next-line playwright/no-wait-for-timeout
  await page.waitForTimeout(300)

  // force: true — bypasses stability check (content re-renders every game tick).
  // PlanningPanel tabs have no aria-selected attribute, so we assert content directly.
  const n1Tab = page.getByTestId('tab-n1')
  await n1Tab.click({ force: true })

  // Either the violations table or the "no violations" empty state must be present
  const table = page.getByTestId('n1-table')
  const empty = page.getByTestId('n1-empty')
  await expect(table.or(empty)).toBeVisible({ timeout: 10_000 })
})
