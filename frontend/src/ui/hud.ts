/**
 * Pure helper functions for HUD display formatting and derivation.
 * No React or store imports — these are independently testable.
 *
 * All functions are designed per docs/engineering/13-hud.md §API.
 */

import type { GridNetworkDto, ViolationDto } from '../api/types'

// ── Clock formatting ──────────────────────────────────────────────────────────

/**
 * Converts accumulated game-time minutes to a "Day N · HH:MM" string.
 *
 * Day count starts at 1 (minute 0 = Day 1 · 00:00).
 * Minutes wrap within a 1440-minute (24 h) day.
 *
 * @example
 * formatGameTime(0)     // "Day 1 · 00:00"
 * formatGameTime(90)    // "Day 1 · 01:30"
 * formatGameTime(1440)  // "Day 2 · 00:00"
 */
export function formatGameTime(gameTimeMinutes: number): string {
  const day = Math.floor(gameTimeMinutes / 1440) + 1
  const minutesInDay = gameTimeMinutes % 1440
  const hours = Math.floor(minutesInDay / 60)
  const minutes = minutesInDay % 60
  return `Day ${day} · ${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}`
}

// ── Load calculation ──────────────────────────────────────────────────────────

/**
 * Returns the raw total active power in MW across all loads.
 * Returns 0 when `network` is null.
 */
export function calculateTotalLoadMw(network: GridNetworkDto | null): number {
  if (!network) return 0
  return network.loads.reduce((sum, l) => sum + l.activePowerMw, 0)
}

/**
 * Sums `activePowerMw` across all loads in the network and formats as a string.
 * Returns `"— MW"` when `network` is null (no session or no data yet).
 */
export function totalLoadMw(network: GridNetworkDto | null): string {
  if (!network) return '— MW'
  return `${calculateTotalLoadMw(network).toFixed(0)} MW`
}

// ── Production cost (#377) ─────────────────────────────────────────────────────

/**
 * Total production cost rate (£/h) across all committed generators,
 * computed as Σ (activePowerMw × marginalCostPerMwh) for each committed
 * generator — i.e. each generator's own cost function evaluated at its
 * current output. Returns 0 when `network` is null or has no generators.
 *
 * Replaces the old system-marginal-cost-only "Price" ticker (#377):
 * that value only reflects the cost of the last (marginal) unit dispatched,
 * not the total cost the player is actually paying to run the grid.
 */
export function calculateTotalProductionCostGbpPerHour(network: GridNetworkDto | null): number {
  if (!network) return 0
  return network.generators.reduce(
    (sum, g) => sum + (g.committed ? g.activePowerMw * g.marginalCostPerMwh : 0),
    0,
  )
}

/**
 * Formats the total production cost rate for HUD display.
 * Returns `"— /h"` when `network` is null or there are no committed generators.
 */
export function totalProductionCostGbpPerHour(network: GridNetworkDto | null): string {
  if (!network) return '— /h'
  const cost = calculateTotalProductionCostGbpPerHour(network)
  if (cost <= 0) return '— /h'
  return `£${Math.round(cost).toLocaleString('en-GB')}/h`
}

// ── Grid health ───────────────────────────────────────────────────────────────

export type HealthSeverity = 'ok' | 'warning' | 'critical'

export interface GridHealth {
  label: 'Grid healthy' | 'N-1 risks' | 'Failure'
  severity: HealthSeverity
}

/**
 * Derives grid health label and severity from the active violation list.
 *
 * - No violations → healthy
 * - Any non-critical violation → N-1 risks (warning)
 * - Any OVERLOAD violation on a line/transformer, or any bus voltage
 *   violation above the critical threshold → Failure (critical)
 *
 * "Critical" is triggered by any OVERLOAD violation exceeding 110% of the
 * element limit. All other violations map to "warning". A future refinement
 * can weight by violation type and severity.
 */
export function gridHealthStatus(violations: ViolationDto[]): GridHealth {
  if (violations.length === 0) {
    return { label: 'Grid healthy', severity: 'ok' }
  }
  const hasCritical = violations.some(
    (v) => v.violationType === 'OVERLOAD' && v.value > v.limit * 1.1,
  )
  if (hasCritical) {
    return { label: 'Failure', severity: 'critical' }
  }
  return { label: 'N-1 risks', severity: 'warning' }
}

// ── Trend arrows (#274) ───────────────────────────────────────────────────────

export type TrendDirection = 'up' | 'down' | 'flat'

/**
 * Derives trend direction from a history array of numeric values.
 *
 * Compares the oldest value in the window against the newest. A change of
 * ≥ 2% of the oldest value (or ≥ 1 unit when the oldest value is near zero)
 * is considered a meaningful trend. Otherwise the trend is flat.
 *
 * @param history - Recent values in chronological order (oldest first). Must
 *   have at least 2 entries; returns `'flat'` otherwise.
 */
export function computeTrend(history: number[]): TrendDirection {
  if (history.length < 2) return 'flat'
  const oldest = history[0]
  const newest = history[history.length - 1]
  const delta = newest - oldest
  const threshold = Math.max(Math.abs(oldest) * 0.02, 1)
  if (delta > threshold) return 'up'
  if (delta < -threshold) return 'down'
  return 'flat'
}

/** Unicode arrow character for a trend direction. */
export const TREND_GLYPH: Record<TrendDirection, string> = { up: '↑', down: '↓', flat: '—' }

// ── Speed steps ───────────────────────────────────────────────────────────────

/** Speed multiplier options shown in the BottomHud speed selector. */
export const SPEED_STEPS = [1, 10, 60, 100] as const
export type SpeedStep = (typeof SPEED_STEPS)[number]
