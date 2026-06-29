import '../shared/e2e-bridge'
import { test, expect } from '@playwright/test'

/**
 * SL — Session Lifecycle
 *
 * SL-01: App loads without JS errors
 * SL-02: POST /api/sessions returns a valid UUID
 * SL-03: First GameStateUpdate arrives within 15 s of page load
 *
 * @see docs/engineering/15-e2e-ci.md §SL-01–03
 */

test('SL-01 app loads without JS errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  await page.goto('/')
  // Bootstrap overlay disappears once session is connected
  await page.waitForSelector('[data-testid="bootstrap-overlay"]', {
    state: 'hidden',
    timeout: 15_000,
  })

  expect(errors).toHaveLength(0)
})

test('SL-02 POST /api/sessions returns valid sessionId', async ({ request }) => {
  const tokenRes = await request.post('/api/auth/token', { data: {} })
  const { token } = await tokenRes.json() as { token: string }

  const res = await request.post('/api/sessions', {
    data: { displayName: 'SL-02', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })

  expect(res.ok()).toBeTruthy()
  const body = await res.json() as { id: string }
  expect(body.id).toMatch(/^[0-9a-f-]{36}$/)

  // Cleanup
  await request.delete(`/api/sessions/${body.id}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
})

test('SL-03 first GameStateUpdate arrives within 15 s', async ({ page }) => {
  await page.goto('/')

  // Wait until the __e2e bridge reports at least one tick
  await page.waitForFunction(
    () => (window.__e2e?.getStore().tickNumber ?? 0) > 0,
    { timeout: 15_000 },
  )

  const tick = await page.evaluate(() => window.__e2e.getStore().tickNumber)
  expect(tick).toBeGreaterThan(0)
})
