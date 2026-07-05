import '../shared/e2e-bridge'
import { test, expect, type Page, type APIRequestContext } from '@playwright/test'

/**
 * TU — Tutorial mode (5-step guided flow)
 *
 * TU-01: TUTORIAL session shows the step 1 overlay on load
 * TU-02: Step 1 auto-advances to step 2 after three ticks
 * TU-03: SetGeneratorOutput command advances step 2 → 3
 * TU-04: Scheduled demand spike fires and advances step 3 → 4
 * TU-05: Pause then resume completes the tutorial (step 5)
 * TU-06: FREE_PLAY sessions never show the tutorial overlay
 *
 * The app's bootstrap resumes the session found in localStorage, so each test
 * creates a TUTORIAL session over REST and seeds storage before first load —
 * same identity, same token, no second session.
 *
 * Step triggers mirror TutorialEngineImpl: 3 ticks (1 tick ≈ 1 s at 1×),
 * SetGeneratorOutput command, demand-spike event (+10 game-min ≈ 1 tick after
 * entering step 3), then pause → resume.
 *
 * @see docs/engineering/15-e2e-ci.md
 */

async function bootstrapTutorial(
  page: Page,
  request: APIRequestContext,
): Promise<{ token: string; sessionId: string }> {
  const tokenRes = await request.post('/api/auth/token', { data: {} })
  const { token, userId } = await tokenRes.json() as { token: string; userId: string }

  const sessionRes = await request.post('/api/sessions', {
    data: { displayName: 'TU e2e', mode: 'TUTORIAL', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })
  const { id } = await sessionRes.json() as { id: string }

  // Seed storage so useSessionBootstrap resumes THIS tutorial session
  await page.addInitScript(
    ({ token: t, userId: u, sessionId: s }) => {
      localStorage.setItem('gridmaster_token', t)
      localStorage.setItem('gridmaster_user_id', u)
      localStorage.setItem('gridmaster_session_id', s)
    },
    { token, userId, sessionId: id },
  )

  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })
  return { token, sessionId: id }
}

async function deleteSession(request: APIRequestContext, token: string, sessionId: string): Promise<void> {
  await request.delete(`/api/sessions/${sessionId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
}

/** Waits for the tutorial overlay to reach the given step. */
async function waitForStep(page: Page, step: number): Promise<void> {
  await page.waitForSelector(`[data-testid="tutorial-overlay"][data-step="${step}"]`, {
    timeout: 25_000,
  })
}

/**
 * Drives a freshly bootstrapped tutorial from step 1 to step 3:
 * waits for the tick-based advance to step 2, then sends the
 * SetGeneratorOutput command the step machine listens for.
 */
async function advanceToStep3(page: Page): Promise<void> {
  await waitForStep(page, 2)

  // Network is populated by the first tick; pick any committed generator
  await page.waitForFunction(
    () => window.__e2e.getStore().network?.generators.some((g) => g.committed) ?? false,
    { timeout: 15_000 },
  )
  const [genId, maxMw] = await page.evaluate(() => {
    const gen = window.__e2e.getStore().network!.generators.find((g) => g.committed)!
    return [gen.id, gen.maxActivePowerMw] as [string, number]
  })

  // 50% of max — safe across all ieee14 generator ratings (same as CM-03)
  await page.evaluate(
    ([id, mw]) => {
      window.__e2e.getStore().sendCommand({ commandType: 'SetGeneratorOutput', payload: { generatorId: id, targetMw: mw } })
    },
    [genId, Math.round(maxMw * 0.5)] as [string, number],
  )
  await waitForStep(page, 3)
}

test('TU-01 tutorial session shows step 1 overlay on load', async ({ page, request }) => {
  const { token, sessionId } = await bootstrapTutorial(page, request)

  const overlay = page.locator('[data-testid="tutorial-overlay"][data-step="1"]')
  await expect(overlay).toBeVisible()
  await expect(overlay).toContainText('Step 1 of 5')

  await deleteSession(request, token, sessionId)
})

test('TU-02 step 1 auto-advances to step 2 after three ticks', async ({ page, request }) => {
  const { token, sessionId } = await bootstrapTutorial(page, request)

  await waitForStep(page, 2)
  await expect(page.locator('[data-testid="tutorial-overlay"]')).toContainText('Step 2 of 5')

  await deleteSession(request, token, sessionId)
})

test('TU-03 SetGeneratorOutput command advances step 2 to step 3', async ({ page, request }) => {
  const { token, sessionId } = await bootstrapTutorial(page, request)

  await advanceToStep3(page)
  await expect(page.locator('[data-testid="tutorial-overlay"]')).toContainText('demand spike')

  await deleteSession(request, token, sessionId)
})

test('TU-04 demand spike fires and advances step 3 to step 4', async ({ page, request }) => {
  const { token, sessionId } = await bootstrapTutorial(page, request)

  await advanceToStep3(page)
  // The spike is scheduled +10 game-minutes (1 tick) after entering step 3
  // and the step machine advances on the tick where it fires.
  await waitForStep(page, 4)
  await expect(page.locator('[data-testid="tutorial-overlay"]')).toContainText('Pause')

  await deleteSession(request, token, sessionId)
})

test('TU-05 pause then resume completes the tutorial', async ({ page, request }) => {
  const { token, sessionId } = await bootstrapTutorial(page, request)

  await advanceToStep3(page)
  await waitForStep(page, 4)

  // Pause…
  await page.locator('[data-testid="hud-playpause-btn"]').click()
  await page.waitForTimeout(1_500)
  // …and resume — the step machine requires both, in order
  await page.locator('[data-testid="hud-playpause-btn"]').click()

  await waitForStep(page, 5)
  const overlay = page.locator('[data-testid="tutorial-overlay"][data-step="5"]')
  await expect(overlay).toContainText('Tutorial complete')

  await deleteSession(request, token, sessionId)
})

test('TU-06 no tutorial overlay in FREE_PLAY session', async ({ page }) => {
  // Fresh context, empty storage → default bootstrap creates a FREE_PLAY session
  await page.goto('/')
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  // Give a few ticks the chance to (incorrectly) surface a step
  await page.waitForTimeout(3_000)
  await expect(page.locator('[data-testid="tutorial-overlay"]')).toHaveCount(0)
})
