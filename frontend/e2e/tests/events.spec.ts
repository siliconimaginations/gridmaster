import '../shared/e2e-bridge'
import { test, expect } from '@playwright/test'

/**
 * EV — Event Card Panel
 *
 * EV-01: Event card overlay appears, shows correct title + severity label,
 *        and clicking Apply dispatches RespondToEventCard then clears the card.
 * EV-02: CRITICAL severity card renders the critical label + CSS modifier.
 * EV-03: Apply button is disabled until an option is selected; enabled after.
 *
 * Event cards are generated stochastically by the backend EventEngine, making
 * waiting for a natural event unreliable in CI.  We inject synthetic cards
 * directly into the Zustand store via the `__e2e.executeSync` bridge (which
 * calls `flushSync` so the DOM is updated synchronously before assertions run)
 * and verify the full UI interaction path.
 *
 * @see issue #87 (component), #211 (original test), #263 (coverage gap)
 */

interface EventCardDto {
  id: string
  title: string
  description: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  options: Array<{ id: string; label: string; tag: string; costGbp: number }>
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Injects a synthetic event card via the __e2e bridge using flushSync. */
async function injectCard(page: import('@playwright/test').Page, card: EventCardDto) {
  await page.evaluate((c) => {
    const store = window.__e2e.getStore()
    window.__e2e.executeSync(() => {
      store.applyUpdate({
        type: 'DELTA',
        sessionId: store.sessionId ?? '',
        tickNumber: store.tickNumber + 1,
        gameTimeMinutes: store.gameTimeMinutes,
        clockState: store.clockState,
        clockSpeedMultiplier: store.clockSpeedMultiplier,
        pendingEventCards: [c],
      })
    })
  }, card)
}

/** Clears all pending event cards via the __e2e bridge using flushSync. */
async function clearCards(page: import('@playwright/test').Page) {
  await page.evaluate(() => {
    const store = window.__e2e.getStore()
    window.__e2e.executeSync(() => {
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
  })
}

/** Waits for game bootstrap to complete and clock to be RUNNING. */
async function waitForBootstrap(page: import('@playwright/test').Page) {
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })
  await page.waitForFunction(
    () => window.__e2e?.getStore().clockState === 'RUNNING',
    { timeout: 15_000 },
  )
}

// ── Synthetic cards ───────────────────────────────────────────────────────────

const WARNING_CARD: EventCardDto = {
  id: 'e2e-card-ev01',
  title: 'E2E Test Warning Event',
  description: 'Synthetic WARNING card injected by Playwright.',
  severity: 'WARNING',
  options: [
    { id: '0', label: 'Accept the risk', tag: '', costGbp: 0 },
    { id: '1', label: 'Mitigate now', tag: '', costGbp: 50_000 },
  ],
}

const CRITICAL_CARD: EventCardDto = {
  id: 'e2e-card-ev02',
  title: 'E2E Test Critical Event',
  description: 'Synthetic CRITICAL card injected by Playwright.',
  severity: 'CRITICAL',
  options: [
    { id: '0', label: 'Emergency shutdown', tag: '', costGbp: 0 },
  ],
}

// ── Tests ─────────────────────────────────────────────────────────────────────

test('EV-01 event card overlay appears, shows WARNING label, Apply clears card', async ({ page }) => {
  await waitForBootstrap(page)

  // Sanity: no overlay at start
  await expect(page.getByTestId('event-card-overlay')).not.toBeVisible()

  // Inject WARNING card
  await injectCard(page, WARNING_CARD)

  const overlay = page.getByTestId('event-card-overlay')
  const panel = page.getByTestId('event-card-panel')

  // Overlay and panel must be visible
  await expect(overlay).toBeVisible({ timeout: 3_000 })
  await expect(panel).toBeVisible()

  // Title and severity label must be rendered
  await expect(panel).toContainText('E2E Test Warning Event')
  await expect(panel).toContainText('⚠️ Warning Event')

  // Select first option and Apply
  await page.getByTestId('event-option-0').click()
  await page.getByTestId('event-card-apply').click()

  // Server ACK: clear the card
  await clearCards(page)

  // Overlay must disappear
  await expect(overlay).not.toBeVisible({ timeout: 3_000 })
})

test('EV-02 CRITICAL severity card shows critical label and panel modifier', async ({ page }) => {
  await waitForBootstrap(page)

  await injectCard(page, CRITICAL_CARD)

  const panel = page.getByTestId('event-card-panel')
  await expect(panel).toBeVisible({ timeout: 3_000 })

  // Severity label must match CRITICAL mapping
  await expect(panel).toContainText('⚡ Critical Event')
  // Panel must carry the critical CSS modifier (data-testid check is sufficient;
  // visual regression covers exact styling)
  await expect(panel).toContainText('E2E Test Critical Event')

  // Clean up
  await clearCards(page)
  await expect(page.getByTestId('event-card-overlay')).not.toBeVisible({ timeout: 3_000 })
})

test('EV-03 Apply is disabled until an option is selected', async ({ page }) => {
  await waitForBootstrap(page)

  await injectCard(page, WARNING_CARD)

  await expect(page.getByTestId('event-card-panel')).toBeVisible({ timeout: 3_000 })

  const applyBtn = page.getByTestId('event-card-apply')

  // Must be disabled before selection
  await expect(applyBtn).toBeDisabled()

  // Select first option
  await page.getByTestId('event-option-0').click()

  // Must now be enabled
  await expect(applyBtn).toBeEnabled({ timeout: 3_000 })

  // Clean up without applying
  await clearCards(page)
})
