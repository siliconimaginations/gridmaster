import { test, expect } from '@playwright/test'

/**
 * PL — Planning Panel
 *
 * PL-01: "Plan Day" button opens the PlanningPanel
 * PL-02: N-1 tab shows either a violations table or the "no violations" empty state
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

  // Either the violations table or the "no violations" empty state must be present
  const table    = page.getByTestId('n1-table')
  const empty    = page.getByTestId('n1-empty')

  await expect(table.or(empty)).toBeVisible({ timeout: 5_000 })
})
