import { useEffect, useMemo, useRef } from 'react'
import { useGameStore } from '../state/useGameStore'
import { useShallow } from 'zustand/react/shallow'
import {
  calculateTotalLoadMw,
  computeTrend,
  formatGameTime,
  gridHealthStatus,
  totalLoadMw,
  TREND_GLYPH,
} from './hud'
import type { HealthSeverity, TrendDirection } from './hud'
import styles from './TopHud.module.css'

/**
 * Top HUD — four pill badges centred at the top of the screen.
 *
 * Displays: game clock · total load (+ trend arrow) · system price ·
 *           grid health (+ trend arrow).
 *
 * Trend arrows compare the metric over the last 5 game ticks (§274):
 *   - Load: ↑ amber (higher demand), ↓ green (falling demand), — grey (stable)
 *   - Health: ↑ red (more violations), ↓ green (fewer violations), — grey
 *
 * All data is read directly from the Zustand store.
 * Non-interactive (pointer-events: none).
 *
 * @see docs/engineering/13-hud.md
 * @see issue #274
 */

const HISTORY_LENGTH = 5

interface MetricSample {
  tick: number
  loadMw: number
  violationCount: number
}

/** CSS class for a load trend arrow (higher load = worse → amber/yellow). */
function loadArrowClass(dir: TrendDirection): string {
  if (dir === 'up') return styles.trendUp
  if (dir === 'down') return styles.trendDown
  return styles.trendFlat
}

/** CSS class for a health (violation-count) trend arrow (more = worse → red). */
function healthArrowClass(dir: TrendDirection): string {
  if (dir === 'up') return styles.trendUpBad
  if (dir === 'down') return styles.trendDownGood
  return styles.trendFlat
}

export function TopHud() {
  const { gameTimeMinutes, clockState, tickNumber, network, violations, healthScore } = useGameStore(useShallow((s) => ({
    gameTimeMinutes: s.gameTimeMinutes,
    clockState: s.clockState,
    tickNumber: s.tickNumber,
    network: s.network,
    violations: s.violations,
    healthScore: s.healthScore,
  })))

  // ── Metric history ring buffer (last HISTORY_LENGTH ticks) ────────────────

  const historyRef = useRef<MetricSample[]>([])

  useEffect(() => {
    if (tickNumber === 0) return   // skip pre-bootstrap ticks
    const sample: MetricSample = {
      tick: tickNumber,
      loadMw: calculateTotalLoadMw(network),
      violationCount: violations.length,
    }
    historyRef.current = [
      ...historyRef.current.filter((h) => h.tick < tickNumber),
      sample,
    ].slice(-HISTORY_LENGTH)
  }, [tickNumber, network, violations])

  // ── Trend computation ──────────────────────────────────────────────────────

  const loadTrend = useMemo<TrendDirection>(() => {
    const hist = historyRef.current
    return computeTrend(hist.map((h) => h.loadMw))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tickNumber])  // re-derive whenever a new tick arrives

  const healthTrend = useMemo<TrendDirection>(() => {
    const hist = historyRef.current
    return computeTrend(hist.map((h) => h.violationCount))
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tickNumber])

  // ── Derived display values ─────────────────────────────────────────────────

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
        <span
          className={`${styles.trendArrow} ${loadArrowClass(loadTrend)}`}
          data-testid="hud-load-trend"
          aria-label={`Load trend: ${loadTrend}`}
        >
          {TREND_GLYPH[loadTrend]}
        </span>
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
        {healthScore != null && (
          <span
            data-testid="hud-health-score"
            style={{ marginLeft: 4, opacity: 0.8, fontSize: '0.85em' }}
          >
            ({healthScore})
          </span>
        )}
        <span
          className={`${styles.trendArrow} ${healthArrowClass(healthTrend)}`}
          data-testid="hud-health-trend"
          aria-label={`Health trend: ${healthTrend}`}
        >
          {TREND_GLYPH[healthTrend]}
        </span>
      </div>
    </div>
  )
}
