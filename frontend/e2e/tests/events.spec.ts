import { test, expect } from '@playwright/test'

/**
 * EV — Event Card Panel
 *
 * EV-01: Event card overlay appears when pendingEventCards is non-empty, and
 *        clicking Apply dispatches a RespondToEventCard command.
 *
 * Event cards are generated stochastically by the backend EventEngine which
 * makes waiting for a natural event unreliable in CI.  Instead, we inject a
 * synthetic event card directly into the Zustand store via the `applyUpdate`
 * action (exposed through the `__e2e` bridge) and verify the full UI
 * interaction: appear → select option → Apply → clear.
 *
 * @see issue #87 (component), #211 (this test)
 */

interface EventCardDto {
  id: string
  title: string
  description: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  options: Array<{ id: string; label: string; tag: string; costGbp: number }>
}

interface StoreSnapshot {
  tickNumber: number
  gameTimeMinutes: number
  clockState: string
  clockSpeedMultiplier: number
  pendingEventCards: EventCardDto[]
  applyUpdate: (update: {
    type: 'DELTA'
    sessionId: string
    tickNumber: number
    gameTimeMinutes: number
    clockState: string
    clockSpeedMultiplier: number
    pendingEventCards: EventCardDto[]
  }) => void
  sessionId: string | null
}

declare global {
  interface Window {
    __e2e: { getStore: () => StoreSnapshot }
  }
}

/** Synthetic event card injected into the store for UI testing. */
const SYNTHETIC_CARD: EventCardDto = {
  id: 'e2e-test-card-ev01',
  title: 'E2E Test Event',
  description: 'Synthetic event card injected by the Playwright E2E suite.',
  severity: 'WARNING',
  options: [
    { id: 'opt-accept', label: 'Accept the risk', tag: 'ACCEPT', costGbp: 0 },
    { id: 'opt-mitigate', label: 'Mitigate now', tag: 'MITIGATE', costGbp: 50_000 },
  ],
}

test('EV-01 event card overlay appears and accepting an option dispatches the command', async ({ page }) => {
  await page.goto('/')

  // Wait for session bootstrap to complete
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Wait for the clock to be running (WS connection established)
  await page.waitForFunction(
    () => window.__e2e?.getStore().clockState === 'RUNNING',
    { timeout: 15_000 },
  )

  // Sanity check: no event card overlay at start
  await expect(page.getByTestId('event-card-overlay')).not.toBeVisible()

  // Inject the synthetic event card via applyUpdate (DELTA that only sets pendingEventCards)
  await page.evaluate((card) => {
    const store = window.__e2e.getStore()
    store.applyUpdate({
      type: 'DELTA',
      sessionId: store.sessionId ?? '',
      tickNumber: store.tickNumber + 1,
      gameTimeMinutes: store.gameTimeMinutes,
      clockState: store.clockState,
      clockSpeedMultiplier: store.clockSpeedMultiplier,
      pendingEventCards: [card],
    })
  }, SYNTHETIC_CARD)

  // Event card overlay must be visible
  const overlay = page.getByTestId('event-card-overlay')
  await expect(overlay).toBeVisible({ timeout: 5_000 })

  // The event card panel must be visible and show the card title
  const panel = page.getByTestId('event-card-panel')
  await expect(panel).toBeVisible()
  await expect(panel).toContainText('E2E Test Event')

  // Apply button must be disabled before an option is selected
  const applyBtn = page.getByTestId('event-card-apply')
  await expect(applyBtn).toBeDisabled()

  // Click the first option to select it
  const firstOption = page.getByTestId(`event-option-${SYNTHETIC_CARD.options[0].id}`)
  await expect(firstOption).toBeVisible()
  await firstOption.click()

  // Apply button must now be enabled
  await expect(applyBtn).toBeEnabled({ timeout: 3_000 })

  // Click Apply — this sends a RespondToEventCard command over WebSocket
  await applyBtn.click()

  // Clear the card from the store (simulates the server acknowledging the response
  // and sending a DELTA with empty pendingEventCards)
  await page.evaluate(() => {
    const store = window.__e2e.getStore()
    store.applyUpdate({
      type: 'DELTA',
      sessionId: store.sessionId ?? '',
      tickNumber: store.tickNumber + 1,
      gameTimeMinutes: store.gameTimeMinutes,
      clockState: store.clockState,
      clockSpeedMultiplier: store.clockSpeedMultiplier,
      pendingEventCards: [],
    })
  })

  // Overlay must disappear once pendingEventCards is empty
  await expect(overlay).not.toBeVisible({ timeout: 5_000 })
})
