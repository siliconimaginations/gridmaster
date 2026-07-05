import { useGameStore } from '../state/useGameStore'
import styles from './ChallengeHUD.module.css'

// ── Helpers ────────────────────────────────────────────────────────────────────

/**
 * Format game-minutes as "HH:mm" for the countdown display.
 */
function formatMinutes(totalMinutes: number): string {
  const h = Math.floor(totalMinutes / 60)
  const m = totalMinutes % 60
  return h > 0
    ? `${h}h ${String(m).padStart(2, '0')}m`
    : `${m}m`
}

/**
 * Derive a colour tier from minutes remaining.
 * ≥ 20 min → green, 10–19 → amber, < 10 → red.
 */
function urgencyClass(
  minutesRemaining: number,
  styles: CSSModuleClasses,
): string {
  if (minutesRemaining >= 20) return styles.urgent0
  if (minutesRemaining >= 10) return styles.urgent1
  return styles.urgent2
}

// CSS Module type helper — avoids any-cast without importing csstype
type CSSModuleClasses = { readonly [key: string]: string }

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * Challenge-mode heads-up display.
 *
 * Anchored to the top-right of the HUD, below TopHud. Shows:
 * - Objective text ("Restore grid stability before the deadline")
 * - Countdown (game-minutes remaining until the 60-minute deadline)
 * - A health/time urgency colour (green → amber → red)
 *
 * Only rendered during CHALLENGE sessions (when `challengeTimeRemainingMinutes`
 * is non-null in the Zustand store).
 */
export function ChallengeHUD() {
  const timeRemaining = useGameStore((s) => s.challengeTimeRemainingMinutes)
  const healthScore   = useGameStore((s) => s.healthScore)

  if (timeRemaining === null || timeRemaining === undefined) return null

  const health = healthScore ?? 0
  const timeClass = urgencyClass(timeRemaining, styles as CSSModuleClasses)
  const healthClass =
    health >= 60 ? styles.healthGood
    : health >= 30 ? styles.healthWarn
    : styles.healthCrit

  return (
    <div
      className={`${styles.panel} ${timeClass}`}
      data-testid="challenge-hud"
      data-time-remaining={timeRemaining}
    >
      <div className={styles.header}>
        <span className={styles.icon}>⚡</span>
        <span className={styles.label}>CHALLENGE</span>
      </div>

      <div className={styles.objective}>
        Restore grid stability before the deadline
      </div>

      <div className={styles.stats}>
        <div className={styles.stat}>
          <div className={`${styles.statValue} ${timeClass}`}>
            {formatMinutes(timeRemaining)}
          </div>
          <div className={styles.statLabel}>Time remaining</div>
        </div>

        <div className={styles.stat}>
          <div className={`${styles.statValue} ${healthClass}`}>
            {health}
          </div>
          <div className={styles.statLabel}>Health score</div>
        </div>
      </div>

      <div className={styles.hint}>
        Maintain health ≥ 60 for 10 ticks after t = 30 min to win
      </div>
    </div>
  )
}
