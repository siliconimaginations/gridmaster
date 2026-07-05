/**
 * Typed REST client for the GridMaster backend API.
 *
 * All endpoints mirror the Kotlin controllers documented in
 * docs/engineering/05-physics-api.md and docs/engineering/06-session-model.md.
 *
 * Auth: the JWT is stored in localStorage under TOKEN_KEY. Every request
 * attaches it as a Bearer header. On a 401 the client re-issues a fresh token
 * (using the stored userId) and retries once.
 */

import type {
  ClockStatusResponse,
  CreateSessionRequest,
  DispatchRequest,
  GridNetworkDto,
  IssueTokenRequest,
  NetworkMutationDto,
  SessionDetailDto,
  SessionSummaryDto,
  TokenResponse,
  UnitCommitmentRequest,
} from './types'

// ── Config ────────────────────────────────────────────────────────────────────

// Empty string → relative URLs (e.g. /api/...) so Vite's proxy forwards to
// localhost:8080 in dev. Set VITE_API_URL for production deployments.
const BASE_URL = (import.meta.env.VITE_API_URL as string | undefined) ?? ''

const TOKEN_KEY = 'gridmaster_token'
const USER_ID_KEY = 'gridmaster_user_id'
const SESSION_ID_KEY = 'gridmaster_session_id'

// ── Auth token helpers ────────────────────────────────────────────────────────

/** Returns the stored JWT, or null if none exists. */
export function getStoredToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

/** Returns the stored userId, or null if none exists. */
export function getStoredUserId(): string | null {
  return localStorage.getItem(USER_ID_KEY)
}

/** Persists the token and userId returned by `POST /api/auth/token`. */
export function setStoredAuth(token: string, userId: string): void {
  localStorage.setItem(TOKEN_KEY, token)
  localStorage.setItem(USER_ID_KEY, userId)
}

/** Removes stored auth — call on explicit sign-out. */
export function clearStoredAuth(): void {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(USER_ID_KEY)
}

/** Returns the stored sessionId for dev-mode bootstrap resume, or null if none exists. */
export function getStoredSessionId(): string | null {
  return localStorage.getItem(SESSION_ID_KEY)
}

/** Persists the sessionId so a future app load can resume the same session. */
export function setStoredSessionId(sessionId: string): void {
  localStorage.setItem(SESSION_ID_KEY, sessionId)
}

/** Removes the stored sessionId — call when a resumed session no longer exists. */
export function clearStoredSessionId(): void {
  localStorage.removeItem(SESSION_ID_KEY)
}

// ── Error type ────────────────────────────────────────────────────────────────

/** Thrown when the server returns a non-2xx status. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly body: unknown,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

// ── Base fetch ────────────────────────────────────────────────────────────────

/**
 * Hard ceiling on any single REST request, in milliseconds.
 *
 * Without this, a request that never gets a response (backend mid-startup,
 * dead proxy socket, wedged request thread) leaves callers hanging forever —
 * the session bootstrap would sit on "Connecting to the grid…" with no error
 * and nothing in the console (#342). With it, the caller gets an `ApiError`
 * (status 0) naming the stuck endpoint, and the bootstrap overlay shows a
 * Retry button instead of spinning forever.
 */
export const REQUEST_TIMEOUT_MS = 30_000

/**
 * Core fetch wrapper.
 * - Attaches Bearer token from localStorage.
 * - On 401, re-issues token then retries once (`retry = false` prevents loops).
 * - Throws `ApiError` on any non-2xx (including the retried 401).
 * - Throws `ApiError` with status 0 when no response arrives within
 *   [REQUEST_TIMEOUT_MS] (see #342).
 * - Returns `undefined` for 204 No Content.
 */
async function apiFetch<T>(path: string, options: RequestInit = {}, retry = true): Promise<T> {
  const token = getStoredToken()
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options.headers as Record<string, string> | undefined),
  }
  if (token) headers['Authorization'] = `Bearer ${token}`

  let res: Response
  try {
    res = await fetch(`${BASE_URL}${path}`, {
      ...options,
      headers,
      signal: options.signal ?? AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    })
  } catch (e) {
    if (e instanceof DOMException && e.name === 'TimeoutError') {
      throw new ApiError(
        0,
        null,
        `No response from the GridMaster backend after ${REQUEST_TIMEOUT_MS / 1000}s: ` +
          `${options.method ?? 'GET'} ${path} — is the backend still starting?`,
      )
    }
    throw e
  }

  if (res.status === 401 && retry) {
    const refreshed = await issueToken({ userId: getStoredUserId() || undefined })
    setStoredAuth(refreshed.token, refreshed.userId)
    return apiFetch<T>(path, options, false)
  }

  if (!res.ok) {
    let body: unknown = null
    try {
      body = await res.json()
    } catch {
      /* non-JSON error body */
    }
    throw new ApiError(res.status, body, `API ${res.status}: ${path}`)
  }

  if (res.status === 204) return undefined as T
  return res.json() as Promise<T>
}

// ── Auth ──────────────────────────────────────────────────────────────────────

/**
 * Issues (or re-issues) a JWT.
 *
 * First launch: omit `userId` — server mints a new UUID.
 * Subsequent launches: pass the stored `userId` to get a fresh token for the
 * same player identity.
 *
 * Does NOT retry on 401 to avoid infinite loops.
 */
export function issueToken(body: IssueTokenRequest = {}): Promise<TokenResponse> {
  return apiFetch<TokenResponse>('/api/auth/token', { method: 'POST', body: JSON.stringify(body) }, false)
}

// ── Sessions ──────────────────────────────────────────────────────────────────

/** Creates a new game session and returns the full session detail. */
export function createSession(req: CreateSessionRequest): Promise<SessionDetailDto> {
  return apiFetch<SessionDetailDto>('/api/sessions', { method: 'POST', body: JSON.stringify(req) })
}

/** Lists all sessions owned by the authenticated player. */
export function listSessions(): Promise<SessionSummaryDto[]> {
  return apiFetch<SessionSummaryDto[]>('/api/sessions')
}

/** Returns the full detail for a single session. */
export function getSession(sessionId: string): Promise<SessionDetailDto> {
  return apiFetch<SessionDetailDto>(`/api/sessions/${sessionId}`)
}

/** Deletes a session. Returns void (204). */
export function deleteSession(sessionId: string): Promise<void> {
  return apiFetch<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' })
}

// ── Clock ─────────────────────────────────────────────────────────────────────

/** Returns the current clock status for a session. */
export function getClockStatus(sessionId: string): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock`)
}

/** Starts the game clock (from STOPPED or initial state). */
export function startClock(sessionId: string): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock/start`, { method: 'POST' })
}

/** Pauses a running clock. */
export function pauseClock(sessionId: string): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock/pause`, { method: 'POST' })
}

/** Resumes a paused clock. */
export function resumeClock(sessionId: string): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock/resume`, { method: 'POST' })
}

/** Sets the clock speed multiplier (1–100). */
export function setClockSpeed(sessionId: string, multiplier: number): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock/speed`, {
    method: 'POST',
    body: JSON.stringify({ multiplier }),
  })
}

/** Stops the clock permanently for this session. */
export function stopClock(sessionId: string): Promise<ClockStatusResponse> {
  return apiFetch<ClockStatusResponse>(`/api/sessions/${sessionId}/clock/stop`, { method: 'POST' })
}

// ── Network ───────────────────────────────────────────────────────────────────

/** Returns the current live network snapshot. */
export function getNetwork(sessionId: string): Promise<GridNetworkDto> {
  return apiFetch<GridNetworkDto>(`/api/sessions/${sessionId}/network`)
}

/**
 * Applies one or more network mutations (e.g. trip a line, set generator output).
 *
 * Prefer sending these as `PlayerCommandMessage` over WebSocket for real-time
 * play — use this endpoint for batch setup or when WebSocket is unavailable.
 */
export function applyMutations(sessionId: string, mutations: NetworkMutationDto[]): Promise<void> {
  return apiFetch<void>(`/api/sessions/${sessionId}/network/mutations`, {
    method: 'POST',
    body: JSON.stringify({ mutations }),
  })
}

// ── Power flow ────────────────────────────────────────────────────────────────

/** Returns the most recent power-flow result (does not re-run). */
export function getLatestPowerFlow(sessionId: string): Promise<unknown> {
  return apiFetch<unknown>(`/api/sessions/${sessionId}/powerflow`)
}

/** Triggers a synchronous AC power-flow run and returns the result. */
export function runPowerFlow(sessionId: string): Promise<unknown> {
  return apiFetch<unknown>(`/api/sessions/${sessionId}/powerflow/run`, { method: 'POST' })
}

// ── Dispatch & unit commitment ─────────────────────────────────────────────────

/** Runs economic dispatch and returns the generator output schedule. */
export function runDispatch(sessionId: string, req: DispatchRequest): Promise<unknown> {
  return apiFetch<unknown>(`/api/sessions/${sessionId}/dispatch`, {
    method: 'POST',
    body: JSON.stringify(req),
  })
}

/**
 * Runs unit commitment over a 24-hour forecast.
 *
 * `req.hourlyForecastMw` must have exactly 24 entries.
 */
export function runUnitCommitment(sessionId: string, req: UnitCommitmentRequest): Promise<unknown> {
  return apiFetch<unknown>(`/api/sessions/${sessionId}/unitcommitment`, {
    method: 'POST',
    body: JSON.stringify(req),
  })
}
