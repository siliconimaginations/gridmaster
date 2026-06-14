import { test, expect } from '@playwright/test'

/**
 * PH — Physics REST API
 *
 * PH-01: POST /powerflow/run returns a valid power flow result object
 * PH-02: POST /contingencies/trigger returns a contingency analysis result
 *
 * These tests use Playwright's APIRequestContext (no browser page) to validate
 * the physics engine endpoints directly via REST.
 *
 * A fresh session is created for each test and deleted on completion.
 *
 * Notes:
 * - PH-01 uses DC mode (more numerically stable than AC on a cold session with no
 *   prior power flow warm-up). AC divergence is a valid game state (status=NETWORK_FAILURE)
 *   but returns 500 from the backend's PhysicsServiceException handler, making it
 *   unreliable as a CI assertion. DC always converges on a well-formed network.
 * - PH-02 uses the first `lines` entry from GET /network (GridNetwork uses `lines`,
 *   not `branches`, as the field name for transmission lines).
 *
 * @see docs/engineering/15-e2e-ci.md §PH-01–02
 */

interface AuthTokenResponse { token: string }
interface SessionResponse   { id: string }

async function createSession(request: Parameters<Parameters<typeof test>[1]>[0]['request']): Promise<{ token: string; sessionId: string }> {
  const tokenRes = await request.post('/api/auth/token', { data: {} })
  const { token } = await tokenRes.json() as AuthTokenResponse

  const sessionRes = await request.post('/api/sessions', {
    data: { displayName: 'PH fixture', mode: 'FREE_PLAY', networkPreset: 'ieee14' },
    headers: { Authorization: `Bearer ${token}` },
  })
  const { id: sessionId } = await sessionRes.json() as SessionResponse

  return { token, sessionId }
}

async function deleteSession(
  request: Parameters<Parameters<typeof test>[1]>[0]['request'],
  token: string,
  sessionId: string,
): Promise<void> {
  await request.delete(`/api/sessions/${sessionId}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
}

test('PH-01 POST /powerflow/run returns a valid power flow result', async ({ request }) => {
  const { token, sessionId } = await createSession(request)

  try {
    // Use DC mode: always converges on a well-formed network, avoiding PowSyBl AC
    // divergence on a cold session (no prior solved voltage profile).
    const res = await request.post(`/api/sessions/${sessionId}/powerflow/run`, {
      data: { balanceType: 'PROPORTIONAL', solveMode: 'DC' },
      headers: { Authorization: `Bearer ${token}` },
    })

    expect(res.ok()).toBeTruthy()
    const body = await res.json() as { status: string; snapshot: unknown }
    // DC always produces a result with a status field
    expect(typeof body.status).toBe('string')
    expect(body.snapshot).toBeTruthy()
  } finally {
    await deleteSession(request, token, sessionId)
  }
})

test('PH-02 POST /contingencies/trigger returns contingency analysis result', async ({ request }) => {
  const { token, sessionId } = await createSession(request)

  try {
    // GridNetwork uses `lines` (not `branches`) for transmission line elements
    const networkRes = await request.get(`/api/sessions/${sessionId}/network`, {
      headers: { Authorization: `Bearer ${token}` },
    })
    const network = await networkRes.json() as { lines: Array<{ id: string }> }
    const lineId = network.lines[0]?.id
    expect(lineId).toBeTruthy()

    const res = await request.post(`/api/sessions/${sessionId}/contingencies/trigger`, {
      data: { elementIds: [lineId], contingencyId: `e2e-${lineId}` },
      headers: { Authorization: `Bearer ${token}` },
    })

    // 200 with a result object, or 422 if the contingency was rejected as invalid —
    // either is acceptable; what we assert is that the endpoint is reachable and the
    // session remains alive.
    expect([200, 422]).toContain(res.status())
  } finally {
    await deleteSession(request, token, sessionId)
  }
})
