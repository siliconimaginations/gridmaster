import { useEffect, useState } from 'react'
import {
  ApiError,
  clearStoredSessionId,
  createSession,
  getSession,
  getStoredSessionId,
  getStoredUserId,
  issueToken,
  setStoredAuth,
  setStoredSessionId,
} from '../api/restClient'
import { useGameStore } from './useGameStore'

/** Lifecycle states for {@link useSessionBootstrap}. */
export type BootstrapStatus = 'bootstrapping' | 'ready' | 'error'

/** Return shape of {@link useSessionBootstrap}. */
export interface BootstrapResult {
  status: BootstrapStatus
  error: string | null
  /** Re-runs the bootstrap flow from scratch (e.g. after the user clicks Retry). */
  retry: () => void
}

/** Display name used for sessions created by the dev-mode bootstrap. */
const DEV_DISPLAY_NAME = 'Dev Session'

/** Network preset used for sessions created by the dev-mode bootstrap. */
const DEV_NETWORK_PRESET = 'ieee14'

/**
 * Dev-mode session bootstrap (#133).
 *
 * `App.tsx` previously rendered the canvas and HUD without ever calling
 * `useGameStore.connect`, so the scene stayed empty and HUD buttons stayed
 * disabled. This hook performs the minimal flow needed to reach a live
 * session on first load:
 *
 * 1. `POST /api/auth/token` — mints a fresh JWT, reusing the stored `userId`
 *    (if any) so the player identity persists across reloads.
 * 2. Resume the stored `sessionId` via `GET /api/sessions/{id}` if it still
 *    exists; otherwise `POST /api/sessions` to create a new one.
 * 3. `useGameStore.connect(sessionId, token)` — opens the WebSocket and
 *    starts the live game-state stream.
 *
 * This intentionally is NOT a polished lobby screen — auto-connect on mount
 * is sufficient to unblock QA (#112). The full lobby UI is planned for
 * Stage 5; see #133 for context.
 */
export function useSessionBootstrap(): BootstrapResult {
  const [status, setStatus] = useState<BootstrapStatus>('bootstrapping')
  const [error, setError] = useState<string | null>(null)
  const [attempt, setAttempt] = useState(0)
  const connect = useGameStore((s) => s.connect)

  useEffect(() => {
    let cancelled = false
    setStatus('bootstrapping')
    setError(null)

    async function run() {
      try {
        const tokenRes = await issueToken({ userId: getStoredUserId() ?? undefined })
        if (cancelled) return
        setStoredAuth(tokenRes.token, tokenRes.userId)

        let sessionId = getStoredSessionId()
        if (sessionId) {
          try {
            await getSession(sessionId)
          } catch (err) {
            // Only a 404 means the stored session is genuinely gone (expired,
            // deleted, or from a different backend instance) — fall through and
            // create a new one. Any other error (500, network failure, etc.) is
            // a real problem and should surface via the outer catch rather than
            // being silently papered over by minting a fresh session.
            if (err instanceof ApiError && err.status === 404) {
              clearStoredSessionId()
              sessionId = null
            } else {
              throw err
            }
          }
        }

        if (!sessionId) {
          const session = await createSession({
            displayName: DEV_DISPLAY_NAME,
            mode: 'FREE_PLAY',
            networkPreset: DEV_NETWORK_PRESET,
          })
          if (cancelled) return
          sessionId = session.id
          setStoredSessionId(sessionId)
        }

        if (cancelled) return
        connect(sessionId, tokenRes.token)
        setStatus('ready')
      } catch (err) {
        if (cancelled) return
        const message =
          err instanceof ApiError
            ? err.message
            : 'Could not reach the GridMaster server — is the backend running?'
        setError(message)
        setStatus('error')
      }
    }

    void run()

    return () => {
      cancelled = true
    }
    // `attempt` is the retry trigger; `connect` is stable (Zustand action reference).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [attempt])

  return {
    status,
    error,
    retry: () => setAttempt((n) => n + 1),
  }
}
