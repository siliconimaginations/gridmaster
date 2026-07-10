import { useEffect, useMemo, useRef } from 'react'
import { useGameStore } from '../state/useGameStore'
import { useShallow } from 'zustand/react/shallow'
import {
  calculateTotalLoadMw,
  computeTrend,
  formatGameTime,
  gridHealthStatus,
  totalLoadMw,
  totalProductionCostGbpPerHour,
  TREND_GLYPH,
} from './hud'
import type { HealthSeverity, TrendDirection } from './hud'
import { HealthSparkline } from './HealthSparkline'
import styles from './TopHud.module.css'

/**
 * Top HUD — four pill badges centred at the top of the screen.
 *
 * Displays: game clock · total load (+ trend arrow) · total production cost ·
 *           grid health (+ trend arrow).
 *
 * The "Cost" pill shows the total production cost rate (£/h) across all
 * committed generators — Σ (output MW × each generator's own marginal cost),
 * i.e. what the player is actually paying to run the grid right now. This
 * replaced a system-marginal-cost-only "Price" ticker (#377), which only
 * reflected the cost of the single most-expensive dispatched unit rather
 * than the total production cost.
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
 * @see issue #377
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
  const {
    gameTimeMinutes,
    clockState,
    tickNumber,
    network,
    violations,
    healthScore,
    healthHistory,
    dailyLoadMultiplier,
    weeklyLoadMultiplier,
    seasonalLoadMultiplier,
    annualGrowthMultiplier,
    calendarSummary,
    weatherState,
    weatherCloudCoverPct,
    weatherWindSpeedMps,
  } = useGameStore(useShallow((s) => ({
    gameTimeMinutes: s.gameTimeMinutes,
    clockState: s.clockState,
    tickNumber: s.tickNumber,
    network: s.network,
    violations: s.violations,
    healthScore: s.healthScore,
    healthHistory: s.healthHistory,
    dailyLoadMultiplier: s.dailyLoadMultiplier,
    weeklyLoadMultiplier: s.weeklyLoadMultiplier,
    seasonalLoadMultiplier: s.seasonalLoadMultiplier,
    annualGrowthMultiplier: s.annualGrowthMultiplier,
    calendarSummary: s.calendarSummary,
    weatherState: s.weatherState,
    weatherCloudCoverPct: s.weatherCloudCoverPct,
    weatherWindSpeedMps: s.weatherWindSpeedMps,
  })))

  // Combined weekly × seasonal × annual-growth multiplier (issue #388), shown
  // as a single compact badge alongside the existing daily-load-curve badge
  // (#383) rather than three separate indicators. null unless all three
  // components have arrived from the server.
  const longTermMultiplier = useMemo(() => {
    if (weeklyLoadMultiplier == null || seasonalLoadMultiplier == null || annualGrowthMultiplier == null) {
      return null
    }
    return weeklyLoadMultiplier * seasonalLoadMultiplier * annualGrowthMultiplier
  }, [weeklyLoadMultiplier, seasonalLoadMultiplier, annualGrowthMultiplier])

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
  const displayedProductionCost = useMemo(() => totalProductionCostGbpPerHour(network), [network])

  const severityClass: Record<HealthSeverity, string> = {
    ok: styles.severityOk,
    warning: styles.severityWarning,
    critical: styles.severityCritical,
  }

  // Compact glyph + label for the weather badge (issue #391). Kept in TopHud rather
  // than a shared constants file since it's the only consumer so far.
  const weatherGlyph: Record<string, string> = {
    CLEAR: '☀',
    PARTLY_CLOUDY: '⛅',
    CLOUDY: '☁',
    OVERCAST: '☁',
    STORM: '⛈',
  }

  return (
    <div className={styles.root} data-testid="top-hud">
      {/* Clock */}
      <div className={styles.pill} data-testid="pill-clock">
        <span className={styles.label}>Time</span>
        {formatGameTime(gameTimeMinutes)}
        {dailyLoadMultiplier != null && (
          <span
            data-testid="hud-daily-load-multiplier"
            title="Current demand vs. daily average, following a typical daily load curve (issue #383)"
            style={{ marginLeft: 4, opacity: 0.75, fontSize: '0.85em' }}
          >
            ×{dailyLoadMultiplier.toFixed(2)}
          </span>
        )}
        {longTermMultiplier != null && (
          <span
            data-testid="hud-long-term-load-multiplier"
            title="Combined weekly + seasonal + annual-growth demand multiplier (issue #388)"
            style={{ marginLeft: 4, opacity: 0.75, fontSize: '0.85em' }}
          >
            ×{longTermMultiplier.toFixed(2)}
          </span>
        )}
        {calendarSummary != null && (
          <span
            data-testid="hud-calendar-summary"
            title="In-game calendar (issue #388) — anchored at session start = Monday, January 1"
            style={{ marginLeft: 6, opacity: 0.6, fontSize: '0.8em' }}
          >
            {calendarSummary}
          </span>
        )}
        {weatherState != null && (
          <span
            data-testid="hud-weather"
            title={`Weather (issue #391): ${weatherState}${weatherCloudCoverPct != null ? `, ${weatherCloudCoverPct.toFixed(0)}% cloud cover` : ''}${weatherWindSpeedMps != null ? `, wind ${weatherWindSpeedMps.toFixed(1)} m/s` : ''}`}
            style={{ marginLeft: 6, opacity: 0.75, fontSize: '0.9em' }}
          >
            {weatherGlyph[weatherState] ?? weatherState}
            {weatherWindSpeedMps != null && (
              <span style={{ marginLeft: 2, fontSize: '0.8em', opacity: 0.8 }}>
                {weatherWindSpeedMps.toFixed(1)}m/s
              </span>
            )}
          </span>
        )}
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

      {/* Total production cost — Σ (output MW × marginal cost) across
          committed generators, replacing the old system-marginal-cost-only
          "Price" ticker (#377) */}
      <div className={styles.pill} data-testid="pill-production-cost">
        <span className={styles.label}>Cost</span>
        <span data-testid="hud-production-cost">{displayedProductionCost}</span>
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
        <HealthSparkline history={healthHistory} />
      </div>
    </div>
  )
}

