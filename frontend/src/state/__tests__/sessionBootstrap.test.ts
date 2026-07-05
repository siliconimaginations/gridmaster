import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '../../api/restClient'
import type { SessionDetailDto, TokenResponse } from '../../api/types'

// ── Mocks ─────────────────────────────────────────────────────────────────────

const mockIssueToken = vi.fn()
const mockCreateSession = vi.fn()
const mockGetSession = vi.fn()
const mockStartClock = vi.fn()
const mockGetStoredUserId = vi.fn()
const mockGetStoredSessionId = vi.fn()
const mockSetStoredAuth = vi.fn()
const mockSetStoredSessionId = vi.fn()
const mockClearStoredSessionId = vi.fn()

vi.mock('../../api/restClient', async () => {
  const actual = await vi.importActual<typeof import('../../api/restClient')>('../../api/restClient')
  return {
    ApiError: actual.ApiError,
    issueToken: (...args: unknown[]) => mockIssueToken(...args),
    createSession: (...args: unknown[]) => mockCreateSession(...args),
    getSession: (...args: unknown[]) => mockGetSession(...args),
    startClock: (...args: unknown[]) => mockStartClock(...args),
    getStoredUserId: () => mockGetStoredUserId(),
    getStoredSessionId: () => mockGetStoredSessionId(),
    setStoredAuth: (...args: unknown[]) => mockSetStoredAuth(...args),
    setStoredSessionId: (...args: unknown[]) => mockSetStoredSessionId(...args),
    clearStoredSessionId: () => mockClearStoredSessionId(),
  }
})

const mockConnect = vi.fn()
const mockSetState = vi.fn()

// useGameStore is called as a selector function AND as useGameStore.setState().
// The mock must expose both the call signature and the static setState method.
vi.mock('../useGameStore', () => {
  const useGameStore = (selector: (s: { connect: typeof mockConnect; sessionInvalidated: boolean }) => unknown) =>
    selector({ connect: mockConnect, sessionInvalidated: false })
  useGameStore.setState = (...args: unknown[]) => mockSetState(...args)
  return { useGameStore }
})

import { useSessionBootstrap } from '../sessionBootstrap'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const TOKEN: TokenResponse = { token: 'jwt-123', userId: 'user-abc', expiresInDays: 7 }
const SESSION: SessionDetailDto = {
  id: 'sess-new',
  userId: 'user-abc',
  mode: 'FREE_PLAY',
  displayName: 'Dev Session',
  gameTimeEpochMinutes: 0,
  clockState: 'STOPPED',
  clockSpeedMultiplier: 1,
  createdAt: '2026-06-08T00:00:00Z',
  updatedAt: '2026-06-08T00:00:00Z',
  completedAt: null,
  availablePresets: ['ieee14'],
}

describe('useSessionBootstrap', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGetStoredUserId.mockReturnValue(null)
    mockGetStoredSessionId.mockReturnValue(null)
    mockIssueToken.mockResolvedValue(TOKEN)
    mockCreateSession.mockResolvedValue(SESSION)
    mockGetSession.mockResolvedValue(SESSION)
    mockStartClock.mockResolvedValue({ state: 'RUNNING' })
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('starts in the bootstrapping state', () => {
    const { result } = renderHook(() => useSessionBootstrap())
    expect(result.current.status).toBe('bootstrapping')
    expect(result.current.error).toBeNull()
  })

  it('issues a token, creates a session, persists both, and connects', async () => {
    const { result } = renderHook(() => useSessionBootstrap())

    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockIssueToken).toHaveBeenCalledWith({ userId: undefined })
    expect(mockSetStoredAuth).toHaveBeenCalledWith(TOKEN.token, TOKEN.userId)
    expect(mockCreateSession).toHaveBeenCalledWith({
      displayName: 'Dev Session',
      mode: 'FREE_PLAY',
      networkPreset: 'ieee14',
    })
    expect(mockSetStoredSessionId).toHaveBeenCalledWith(SESSION.id)
    expect(mockConnect).toHaveBeenCalledWith(SESSION.id, TOKEN.token)
  })

  it('optimistically sets clockState to RUNNING after startClock succeeds', async () => {
    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(mockSetState).toHaveBeenCalledWith({ clockState: 'RUNNING' })
  })

  it('passes the stored userId through to issueToken to resume identity', async () => {
    mockGetStoredUserId.mockReturnValue('user-abc')

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockIssueToken).toHaveBeenCalledWith({ userId: 'user-abc' })
  })

  it('resumes a stored session that still exists instead of creating a new one', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-existing')
    mockGetSession.mockResolvedValue({ ...SESSION, id: 'sess-existing', clockState: 'RUNNING' })

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockGetSession).toHaveBeenCalledWith('sess-existing')
    expect(mockCreateSession).not.toHaveBeenCalled()
    expect(mockConnect).toHaveBeenCalledWith('sess-existing', TOKEN.token)
  })

  it('resumes a paused stored session', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-paused')
    mockGetSession.mockResolvedValue({ ...SESSION, id: 'sess-paused', clockState: 'PAUSED' })

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockCreateSession).not.toHaveBeenCalled()
    expect(mockConnect).toHaveBeenCalledWith('sess-paused', TOKEN.token)
  })

  it('creates a new session when the stored session is completed (game over) (#334)', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-done')
    mockGetSession.mockResolvedValue({
      ...SESSION,
      id: 'sess-done',
      clockState: 'RUNNING',
      completedAt: '2026-07-05T00:00:00Z',
    })

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockClearStoredSessionId).toHaveBeenCalled()
    expect(mockCreateSession).toHaveBeenCalled()
    expect(mockConnect).toHaveBeenCalledWith(SESSION.id, TOKEN.token)
  })

  it('creates a new session when the stored session is permanently stopped (#334)', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-stopped')
    mockGetSession.mockResolvedValue({ ...SESSION, id: 'sess-stopped', clockState: 'STOPPED' })

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockClearStoredSessionId).toHaveBeenCalled()
    expect(mockCreateSession).toHaveBeenCalled()
    expect(mockConnect).toHaveBeenCalledWith(SESSION.id, TOKEN.token)
  })

  it('creates a new session when the stored session no longer exists', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-stale')
    mockGetSession.mockRejectedValue(new ApiError(404, null, 'API 404: not found'))

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('ready'))

    expect(mockClearStoredSessionId).toHaveBeenCalled()
    expect(mockCreateSession).toHaveBeenCalled()
    expect(mockConnect).toHaveBeenCalledWith(SESSION.id, TOKEN.token)
  })

  it('does not paper over non-404 errors when resuming a stored session', async () => {
    mockGetStoredSessionId.mockReturnValue('sess-existing')
    mockGetSession.mockRejectedValue(new ApiError(500, null, 'API 500: /api/sessions/sess-existing'))

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('error'))

    // A 500 means something is genuinely wrong server-side — surface it rather
    // than silently treating the session as stale and minting a new one.
    expect(mockClearStoredSessionId).not.toHaveBeenCalled()
    expect(mockCreateSession).not.toHaveBeenCalled()
    expect(mockConnect).not.toHaveBeenCalled()
    expect(result.current.error).toMatch(/API 500/)
  })

  it('enters the error state with a friendly message when the backend is unreachable', async () => {
    mockIssueToken.mockRejectedValue(new TypeError('Failed to fetch'))

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('error'))

    expect(result.current.error).toMatch(/backend running/i)
    expect(mockConnect).not.toHaveBeenCalled()
  })

  it('surfaces API errors with their status code', async () => {
    mockIssueToken.mockRejectedValue(new ApiError(500, null, 'API 500: /api/auth/token'))

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('error'))

    expect(result.current.error).toMatch(/API 500/)
  })

  it('retry re-runs the bootstrap flow and can recover from a prior failure', async () => {
    mockIssueToken.mockRejectedValueOnce(new TypeError('Failed to fetch'))

    const { result } = renderHook(() => useSessionBootstrap())
    await waitFor(() => expect(result.current.status).toBe('error'))

    act(() => result.current.retry())

    await waitFor(() => expect(result.current.status).toBe('ready'))
    expect(mockConnect).toHaveBeenCalledWith(SESSION.id, TOKEN.token)
  })
})
