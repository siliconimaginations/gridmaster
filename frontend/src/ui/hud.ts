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
 * Sums `activePowerMw` across all loads in the network.
 * Returns `"— MW"` when `network` is null (no session or no data yet).
 */
export function totalLoadMw(network: GridNetworkDto | null): string {
  if (!network) return '— MW'
  const total = network.loads.reduce((sum, l) => sum + l.activePowerMw, 0)
  return `${total.toFixed(0)} MW`
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
 * For the MVP, "critical" is defined as any violation present at all
 * (conservative). A future refinement can weight by violation type.
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

// ── Speed steps ───────────────────────────────────────────────────────────────

/** Speed multiplier options shown in the BottomHud speed selector. */
export const SPEED_STEPS = [1, 10, 60, 100] as const
export type SpeedStep = (typeof SPEED_STEPS)[number]
