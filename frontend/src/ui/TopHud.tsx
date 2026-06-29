import { useMemo } from 'react'
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
  const { gameTimeMinutes, clockState, tickNumber, network, violations } = useGameStore(useShallow((s) => ({
    gameTimeMinutes: s.gameTimeMinutes,
    clockState: s.clockState,
    tickNumber: s.tickNumber,
    network: s.network,
    violations: s.violations,
  })))

  const health = useMemo(() => gridHealthStatus(violations), [violations])
  const displayedLoad = useMemo(() => totalLoadMw(network), [network])
  const displayedPrice = useMemo(() => {
    const smc = network?.systemMarginalCostPerMwh
    if (smc == null || smc <= 0) return null
    return `£${Math.round(smc)}/MWh`
  }, [network])

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
        <span data-testid="hud-clock-state" style={{ display: 'none' }}>{clockState}</span>
        <span data-testid="hud-tick-number" style={{ display: 'none' }}>{tickNumber}</span>
      </div>

      {/* Load */}
      <div className={styles.pill} data-testid="pill-load">
        <span className={styles.label}>Load</span>
        <span data-testid="hud-total-load">{displayedLoad}</span>
      </div>

      {/* Price — system marginal cost from last dispatch solve (#283) */}
      <div className={styles.pill} data-testid="pill-price">
        <span className={styles.label}>Price</span>
        <span data-testid="hud-price">{displayedPrice ?? '— /MWh'}</span>
      </div>

      {/* Health */}
      <div
        className={`${styles.pill} ${severityClass[health.severity]}`}
        data-testid="pill-health"
        data-severity={health.severity}
      >
        <span data-testid="hud-grid-health">{health.severity.toUpperCase()}</span>
        {' '}{health.label}
      </div>
    </div>
  )
}
