import { test, expect } from '@playwright/test'

/**
 * IP — Inspector Panel
 *
 * IP-01: Selecting a network element opens InspectorPanel; clicking the backdrop closes it.
 *
 * The inspector is driven by `selectedElement` in the Zustand store.  In the
 * real game the player clicks a Babylon.js mesh; here we drive it through the
 * `__e2e` bridge so the test is independent of canvas pixel positions.
 *
 * @see docs/ux/02-component-inspector.md
 * @see issue #86 (component), #209 (this test)
 */

interface StoreSnapshot {
  tickNumber: number
  network: {
    generators: Array<{ id: string; activePowerMw: number; committed: boolean }>
  } | null
  selectedElement: { elementType: string; elementId: string } | null
  selectElement: (info: { elementType: string; elementId: string } | null) => void
}

declare global {
  interface Window {
    __e2e: { getStore: () => StoreSnapshot }
  }
}

test('IP-01 selecting a generator opens InspectorPanel; backdrop click closes it', async ({ page }) => {
  await page.goto('/')

  // Wait for session bootstrap to complete
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Wait for the first WebSocket tick so the store has a fully hydrated network
  await page.waitForFunction(
    () => (window.__e2e?.getStore().tickNumber ?? 0) > 0,
    { timeout: 15_000 },
  )

  // Discover a generator ID from the live store
  const genId = await page.evaluate(
    () => window.__e2e.getStore().network!.generators[0].id,
  )
  expect(genId).toBeTruthy()

  // Inspector should not be visible before any element is selected
  await expect(page.getByTestId('inspector-panel')).not.toBeVisible()

  // Select the generator via the store bridge (simulates a Babylon.js canvas click)
  await page.evaluate(
    (id) => window.__e2e.getStore().selectElement({ elementType: 'GENERATOR', elementId: id }),
    genId,
  )

  // Verify the store reflected the selection before asserting DOM visibility
  await page.waitForFunction(
    () => window.__e2e?.getStore().selectedElement !== null,
    { timeout: 5_000 },
  )

  // InspectorPanel must now be visible
  const panel = page.getByTestId('inspector-panel')
  await expect(panel).toBeVisible({ timeout: 5_000 })

  // Header must mention the element ID so we know the right card rendered
  await expect(panel).toContainText(genId)

  // Click the invisible backdrop to close the inspector (simulates click-away)
  await page.getByTestId('inspector-backdrop').click()

  // Panel should disappear
  await expect(panel).not.toBeVisible({ timeout: 5_000 })

  // Store must reflect the cleared selection
  const selected = await page.evaluate(() => window.__e2e.getStore().selectedElement)
  expect(selected).toBeNull()
})
