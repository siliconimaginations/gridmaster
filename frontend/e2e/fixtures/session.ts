import { test as base } from '@playwright/test'

interface SessionFixtures {
  /** JWT for the test user. */
  token: string
  /** ID of an isolated session created for (and deleted after) the test. */
  sessionId: string
}

/**
 * Extended `test` with `token` and `sessionId` fixtures.
 *
 * `token` is declared first so `sessionId` can depend on it — avoids issuing
 * a second token and creating an orphaned identity.
 *
 * Teardown: `DELETE /api/sessions/{id}` stops the clock and removes the row,
 * keeping the test database clean between runs.
 *
 * @see docs/engineering/15-e2e-ci.md §Session fixture
 */
export const test = base.extend<SessionFixtures>({
  token: async ({ request }, use) => {
    const res = await request.post('/api/auth/token', { data: {} })
    const { token } = await res.json() as { token: string }
    await use(token)
  },

  sessionId: async ({ request, token }, use) => {
    const sessionRes = await request.post('/api/sessions', {
      data: { displayName: 'E2E Session', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
      headers: { Authorization: `Bearer ${token}` },
    })
    const { id } = await sessionRes.json() as { id: string }

    await use(id)

    // Teardown — stop the clock and remove the session row
    await request.delete(`/api/sessions/${id}`, {
      headers: { Authorization: `Bearer ${token}` },
    })
  },
})

export { expect } from '@playwright/test'
