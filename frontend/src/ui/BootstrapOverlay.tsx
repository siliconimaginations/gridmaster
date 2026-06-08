import styles from './BootstrapOverlay.module.css'
import type { BootstrapStatus } from '../state/sessionBootstrap'

/** Props for {@link BootstrapOverlay}. */
export interface BootstrapOverlayProps {
  /** Current bootstrap lifecycle state — overlay renders nothing for `'ready'`. */
  status: BootstrapStatus
  /** Human-readable failure message, shown when `status === 'error'`. */
  error: string | null
  /** Invoked when the player clicks Retry after a failed bootstrap. */
  onRetry: () => void
}

/**
 * Full-screen overlay shown while {@link useSessionBootstrap} is connecting
 * to the backend, or when it fails. Disappears once the session reaches
 * `'ready'` and `useGameStore.connect` has been called.
 *
 * Renders nothing when `status === 'ready'` so callers can mount it
 * unconditionally alongside the canvas and HUD.
 *
 * @see docs/engineering/13-hud.md
 */
export function BootstrapOverlay({ status, error, onRetry }: BootstrapOverlayProps) {
  if (status === 'ready') return null

  return (
    <div className={styles.root} data-testid="bootstrap-overlay">
      <div className={styles.card}>
        {status === 'bootstrapping' && (
          <p className={styles.message} data-testid="bootstrap-loading">
            Connecting to the grid…
          </p>
        )}
        {status === 'error' && (
          <>
            <p className={styles.errorMessage} data-testid="bootstrap-error">
              {error ?? 'Could not start a session.'}
            </p>
            <button className={styles.retryBtn} onClick={onRetry} data-testid="bootstrap-retry">
              Retry
            </button>
          </>
        )}
      </div>
    </div>
  )
}
