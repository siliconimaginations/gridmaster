import { useGameStore } from '../state/useGameStore'
import { useShallow } from 'zustand/react/shallow'
import { formatGameTime, gridHealthStatus, totalLoadMw } from './hud'
import type { HealthSeverity } from './hud'
import styles from './TopHud.module.css'

/**
 * Top HUD — four pill badges centred at the top of the screen.
 *
 * Displays: game clock · total load · system price · grid health.
 * All data is read directly from the Zustand store.
 * Non-interactive (pointer-events: none); health pill click will be
 * wired to the N-1 panel in issue #86.
 *
 * @see docs/engineering/13-hud.md
 */
export function TopHud() {
  const { gameTimeMinutes, network, violations } = useGameStore(useShallow((s) => ({
    gameTimeMinutes: s.gameTimeMinutes,
    network: s.network,
    violations: s.violations,
  })))

  // TODO: #115 consider useMemo for gridHealthStatus + totalLoadMw if profiling shows TopHud hot
  const health = gridHealthStatus(violations)

  const severityClass: Record<HealthSeverity, string> = {
    ok: styles.severityOk,
    warning: styles.severityWarning,
    critical: styles.severityCritical,
  }

  return (
    <div className={styles.root} data-testid="top-hud">
      {/* Clock */}
      <div className={styles.pill} data-testid="pill-clock">
        <span className={styles.label}>Time</span>
        {formatGameTime(gameTimeMinutes)}
      </div>

      {/* Load */}
      <div className={styles.pill} data-testid="pill-load">
        <span className={styles.label}>Load</span>
        {totalLoadMw(network)}
      </div>

      {/* Price — deferred until backend sends systemMarginalPrice */}
      {/* TODO: wire to store once GameStateUpdate carries price field */}
      <div className={styles.pill} data-testid="pill-price">
        <span className={styles.label}>Price</span>
        — /MWh
      </div>

      {/* Health */}
      <div
        className={`${styles.pill} ${severityClass[health.severity]}`}
        data-testid="pill-health"
        data-severity={health.severity}
      >
        {health.label}
      </div>
    </div>
  )
}
