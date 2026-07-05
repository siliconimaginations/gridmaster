import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  clearStoredAuth,
  createSession,
  deleteSession,
  getClockStatus,
  getStoredToken,
  getStoredUserId,
  issueToken,
  listSessions,
  pauseClock,
  setStoredAuth,
  startClock,
} from '../restClient'

// ── Helpers ───────────────────────────────────────────────────────────────────

function mockFetch(status: number, body: unknown, ok = status >= 200 && status < 300) {
  const jsonFn = vi.fn().mockResolvedValue(body)
  return vi.fn().mockResolvedValue({ ok, status, json: jsonFn } as unknown as Response)
}

// ── Auth storage ──────────────────────────────────────────────────────────────

describe('auth token storage', () => {
  beforeEach(() => clearStoredAuth())

  it('returns null before any token is stored', () => {
    expect(getStoredToken()).toBeNull()
    expect(getStoredUserId()).toBeNull()
  })

  it('persists token and userId via setStoredAuth', () => {
    setStoredAuth('tok123', 'user-abc')
    expect(getStoredToken()).toBe('tok123')
    expect(getStoredUserId()).toBe('user-abc')
  })

  it('clears both values on clearStoredAuth', () => {
    setStoredAuth('tok123', 'user-abc')
    clearStoredAuth()
    expect(getStoredToken()).toBeNull()
    expect(getStoredUserId()).toBeNull()
  })
})

// ── issueToken ────────────────────────────────────────────────────────────────

describe('issueToken', () => {
  beforeEach(() => clearStoredAuth())
  afterEach(() => { vi.restoreAllMocks() })

  it('POSTs to /api/auth/token with empty body on first launch', async () => {
    const fetchMock = mockFetch(200, { token: 't', userId: 'u1', expiresInDays: 30 })
    vi.stubGlobal('fetch', fetchMock)

    await issueToken()

    expect(fetchMock).toHaveBeenCalledOnce()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/auth/token')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({})
  })

  it('POSTs userId when provided', async () => {
    const fetchMock = mockFetch(200, { token: 't2', userId: 'u2', expiresInDays: 30 })
    vi.stubGlobal('fetch', fetchMock)

    await issueToken({ userId: 'u2' })

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(JSON.parse(init.body as string)).toEqual({ userId: 'u2' })
  })

  it('returns the TokenResponse from the server', async () => {
    const response = { token: 'jwt', userId: 'user1', expiresInDays: 30 }
    vi.stubGlobal('fetch', mockFetch(200, response))

    const result = await issueToken()
    expect(result).toEqual(response)
  })

  it('throws ApiError on 400', async () => {
    vi.stubGlobal('fetch', mockFetch(400, { message: 'bad' }, false))

    await expect(issueToken()).rejects.toThrow(ApiError)
  })
})

// ── Auth header + 401 retry ───────────────────────────────────────────────────

describe('apiFetch — auth header + 401 retry', () => {
  beforeEach(() => clearStoredAuth())
  afterEach(() => { vi.restoreAllMocks() })

  it('attaches Bearer token when stored', async () => {
    setStoredAuth('my-token', 'uid')
    const fetchMock = mockFetch(200, [])
    vi.stubGlobal('fetch', fetchMock)

    await listSessions()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['Authorization']).toBe('Bearer my-token')
  })

  it('does not attach Authorization when no token stored', async () => {
    const fetchMock = mockFetch(200, [])
    vi.stubGlobal('fetch', fetchMock)

    await listSessions()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect((init.headers as Record<string, string>)['Authorization']).toBeUndefined()
  })

  it('retries with fresh token on 401', async () => {
    setStoredAuth('expired', 'uid1')
    const newToken = { token: 'fresh', userId: 'uid1', expiresInDays: 30 }
    const sessions = [{ id: 's1' }]

    let callCount = 0
    const fetchMock = vi.fn().mockImplementation(() => {
      callCount++
      if (callCount === 1) {
        // First call: 401 on the original request
        return Promise.resolve({ ok: false, status: 401, json: vi.fn().mockResolvedValue({}) })
      } else if (callCount === 2) {
        // Second call: issueToken refresh
        return Promise.resolve({ ok: true, status: 200, json: vi.fn().mockResolvedValue(newToken) })
      } else {
        // Third call: retry the original request with fresh token
        return Promise.resolve({ ok: true, status: 200, json: vi.fn().mockResolvedValue(sessions) })
      }
    })
    vi.stubGlobal('fetch', fetchMock)

    const result = await listSessions()
    expect(result).toEqual(sessions)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(getStoredToken()).toBe('fresh')
  })
})

// ── apiFetch — request timeout (#342) ────────────────────────────────────────

describe('apiFetch — request timeout', () => {
  beforeEach(() => clearStoredAuth())
  afterEach(() => { vi.restoreAllMocks() })

  it('passes an abort signal so hung requests cannot spin forever', async () => {
    const fetchMock = mockFetch(200, [])
    vi.stubGlobal('fetch', fetchMock)

    await listSessions()

    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(init.signal).toBeInstanceOf(AbortSignal)
  })

  it('maps a fetch TimeoutError to ApiError status 0 naming the endpoint', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValue(new DOMException('The operation timed out.', 'TimeoutError'))
    vi.stubGlobal('fetch', fetchMock)

    const err = await listSessions().catch((e: unknown) => e)

    expect(err).toBeInstanceOf(ApiError)
    expect((err as ApiError).status).toBe(0)
    expect((err as ApiError).message).toContain('GET /api/sessions')
  })

  it('rethrows non-timeout fetch failures unchanged', async () => {
    const boom = new TypeError('Failed to fetch')
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(boom))

    const err = await listSessions().catch((e: unknown) => e)

    expect(err).toBe(boom)
  })
})

// ── Session endpoints ─────────────────────────────────────────────────────────

describe('createSession', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('POSTs to /api/sessions', async () => {
    const detail = { id: 's1', userId: 'u1', mode: 'TUTORIAL', displayName: 'Test' }
    const fetchMock = mockFetch(200, detail)
    vi.stubGlobal('fetch', fetchMock)

    const req = { displayName: 'Test', networkPreset: 'ieee14' }
    const result = await createSession(req)

    expect(result).toEqual(detail)
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/sessions')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual(req)
  })
})

describe('deleteSession', () => {
  afterEach(() => { vi.restoreAllMocks() })

  it('DELETEs the session and returns undefined for 204', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204 } as Response)
    vi.stubGlobal('fetch', fetchMock)

    const result = await deleteSession('sess1')
    expect(result).toBeUndefined()
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/api/sessions/sess1')
    expect(init.method).toBe('DELETE')
  })
})

// ── Clock endpoints ───────────────────────────────────────────────────────────

describe('clock endpoints', () => {
  afterEach(() => { vi.restoreAllMocks() })

  const clockStatus = { clockState: 'RUNNING', speedMultiplier: 1, gameTimeMinutes: 0, tickCount: 0, autoSlowed: false }

  it('getClockStatus GETs the correct path', async () => {
    vi.stubGlobal('fetch', mockFetch(200, clockStatus))
    const result = await getClockStatus('sess1')
    expect(result).toEqual(clockStatus)
  })

  it('startClock POSTs to /clock/start', async () => {
    const fetchMock = mockFetch(200, clockStatus)
    vi.stubGlobal('fetch', fetchMock)
    await startClock('sess1')
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/clock/start')
    expect(init.method).toBe('POST')
  })

  it('pauseClock POSTs to /clock/pause', async () => {
    const fetchMock = mockFetch(200, clockStatus)
    vi.stubGlobal('fetch', fetchMock)
    await pauseClock('sess1')
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit]
    expect(url).toContain('/clock/pause')
  })
})
