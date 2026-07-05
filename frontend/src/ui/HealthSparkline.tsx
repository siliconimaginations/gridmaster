/**
 * Inline SVG sparkline of the last 30 health-score samples (0-100).
 * Rendered inside the TopHud health pill. No external chart library.
 *
 * Colour tracks the most recent sample: green ≥ 60, amber 30-59, red < 30 —
 * same thresholds as the tutorial/health UI copy.
 *
 * Renders nothing until at least 3 samples exist (issue #333 acceptance).
 */
export function HealthSparkline({ history = [] }: { history?: number[] }) {
  if (history.length < 3) return null

  const points = history
    .map((v, i) => {
      const x = ((i / (history.length - 1)) * 58 + 1).toFixed(1)
      const y = (19 - (clamp(v, 0, 100) / 100) * 18).toFixed(1)
      return `${x},${y}`
    })
    .join(' ')

  const last = clamp(history[history.length - 1], 0, 100)
  const color = last >= 60 ? '#34d399' : last >= 30 ? '#fbbf24' : '#f87171'

  return (
    <svg
      width={60}
      height={20}
      viewBox="0 0 60 20"
      style={{ marginLeft: 6, verticalAlign: 'middle' }}
      data-testid="hud-health-sparkline"
      aria-hidden="false"
      role="img"
    >
      <title>Health last {history.length} ticks</title>
      <polyline
        fill="none"
        strokeWidth={1.5}
        strokeLinejoin="round"
        strokeLinecap="round"
        points={points}
        stroke={color}
        data-testid="hud-health-sparkline-line"
      />
    </svg>
  )
}

/** Clamps `v` into the inclusive range [`lo`, `hi`]. */
function clamp(v: number, lo: number, hi: number): number {
  return Math.max(lo, Math.min(hi, v))
}
