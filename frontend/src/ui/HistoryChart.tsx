import { useMemo } from 'react'
import type { HistorySampleDto } from '../api/types'
import styles from './DispatchPanel.module.css'

const CHART_WIDTH = 560
const CHART_HEIGHT = 110

/**
 * Upper bound on the number of points actually drawn in the SVG polyline,
 * regardless of how many raw samples come back from the server.
 *
 * At 10 simulated minutes/tick a month of history is ~4,320 samples (issue
 * #392) — cheap to hold in memory, but rendering that many polyline vertices
 * on every range change is unnecessary work for a ~560px-wide chart where
 * most of those points would land on the same pixel column anyway. Bucket
 * decimation below keeps the line visually smooth (average per bucket,
 * not a naive stride-sample) while bounding render cost.
 */
const MAX_RENDERED_POINTS = 360

/**
 * Hand-rolled inline SVG line chart of total load vs. total generation over
 * a simulated-time window (issue #392).
 *
 * Design note: no charting library (recharts, chart.js, etc.) is in
 * frontend/package.json, and this codebase already has an established
 * "hand-rolled inline SVG, no external chart dependency" convention —
 * HealthSparkline (30-point HUD sparkline) and DispatchPanel's own
 * CostStackSection (CSS bar chart) both follow it. Even though this chart's
 * data volume is far larger (up to ~4,320 points for a month range vs. 30 for
 * the sparkline), a plain two-series polyline chart doesn't need a general
 * charting library's feature set (tooltips, zoom, multiple chart types) —
 * client-side bucket decimation (see [MAX_RENDERED_POINTS]) is enough to keep
 * it cheap to render at any range. Adding a dependency for one chart would be
 * a heavier cost than the hand-rolled approach for this scope.
 */
export function HistoryChart({ samples }: { samples: HistorySampleDto[] }) {
  const decimated = useMemo(() => decimate(samples, MAX_RENDERED_POINTS), [samples])

  if (decimated.length < 2) {
    return (
      <div className={styles.historyEmpty} data-testid="history-chart-empty">
        Not enough history yet — keep the clock running to build up a trend.
      </div>
    )
  }

  const minTime = decimated[0].gameTimeMinutes
  const maxTime = decimated[decimated.length - 1].gameTimeMinutes
  const timeSpan = Math.max(maxTime - minTime, 1)
  const maxValue = Math.max(1, ...decimated.map((d) => Math.max(d.totalLoadMw, d.totalGenerationMw)))

  const toPoints = (key: 'totalLoadMw' | 'totalGenerationMw') =>
    decimated
      .map((d) => {
        const x = ((d.gameTimeMinutes - minTime) / timeSpan) * CHART_WIDTH
        const y = CHART_HEIGHT - (d[key] / maxValue) * CHART_HEIGHT
        return `${x.toFixed(1)},${y.toFixed(1)}`
      })
      .join(' ')

  return (
    <div data-testid="history-chart-wrapper">
      <svg
        width="100%"
        height={CHART_HEIGHT}
        viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`}
        preserveAspectRatio="none"
        data-testid="history-chart"
        role="img"
        aria-label="Total load and generation over the selected time range"
      >
        <title>Load vs. generation ({decimated.length} points shown)</title>
        <polyline
          fill="none"
          stroke="#f87171"
          strokeWidth={1.5}
          strokeLinejoin="round"
          points={toPoints('totalLoadMw')}
          data-testid="history-chart-load-line"
        />
        <polyline
          fill="none"
          stroke="#22d3ee"
          strokeWidth={1.5}
          strokeLinejoin="round"
          points={toPoints('totalGenerationMw')}
          data-testid="history-chart-gen-line"
        />
      </svg>
      <div className={styles.historyLegend}>
        <span className={styles.historyLegendItem}>
          <span className={styles.historyLegendSwatchLoad} /> Load
        </span>
        <span className={styles.historyLegendItem}>
          <span className={styles.historyLegendSwatchGen} /> Generation
        </span>
      </div>
    </div>
  )
}

/**
 * Bucket-averages `samples` down to at most `maxPoints` points, oldest first.
 * Each bucket's output point uses the average value of the bucket (smoother
 * than naive stride-sampling, which can alias/skip spikes) and the
 * `gameTimeMinutes` of the bucket's last sample (so the x-axis still spans
 * the full range).
 */
function decimate(samples: HistorySampleDto[], maxPoints: number): HistorySampleDto[] {
  if (samples.length <= maxPoints) return samples

  const bucketSize = samples.length / maxPoints
  const result: HistorySampleDto[] = []
  for (let i = 0; i < maxPoints; i++) {
    const start = Math.floor(i * bucketSize)
    const end = Math.max(start + 1, Math.floor((i + 1) * bucketSize))
    const bucket = samples.slice(start, Math.min(end, samples.length))
    if (bucket.length === 0) continue
    const n = bucket.length
    result.push({
      gameTimeMinutes: bucket[n - 1].gameTimeMinutes,
      totalLoadMw: bucket.reduce((sum, s) => sum + s.totalLoadMw, 0) / n,
      totalGenerationMw: bucket.reduce((sum, s) => sum + s.totalGenerationMw, 0) / n,
    })
  }
  return result
}
