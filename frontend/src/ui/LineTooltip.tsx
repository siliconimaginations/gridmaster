import { useGameStore } from '../state/useGameStore'
import styles from './LineTooltip.module.css'

/** Screen-space pointer position where the tooltip should be anchored. */
export interface HoverPosition {
  x: number
  y: number
}

function fmt(value: number, decimals = 1): string {
  return value.toFixed(decimals)
}

function severityClass(pct: number): string {
  if (pct > 90) return styles.valueCritical
  if (pct > 70) return styles.valueWarning
  return ''
}

function borderClass(pct: number): string {
  if (pct > 90) return styles.tooltipCritical
  if (pct > 70) return styles.tooltipWarning
  return ''
}

/**
 * Lightweight hover tooltip for transmission lines and transformers (#395).
 *
 * Both line and transformer meshes are tagged `elementType: 'LINE'` in
 * MeshRegistry (see lineMesh.ts / SceneManager.ts), so this single component
 * covers both element kinds without extra wiring.
 *
 * Positioned near the cursor via absolute + translate, following the same
 * floating-panel style as InspectorPanel/TutorialOverlay but intentionally
 * un-interactive (`pointer-events: none`) so it never steals the pick ray
 * that SceneManager uses for hover/click.
 *
 * Loading severity thresholds (70% warning / 90% critical) match the
 * renderer color-coding unified in model/GridGraph.ts and lineMesh.ts (#395).
 */
export function LineTooltip({ position }: { position: HoverPosition | null }) {
  const { hoveredElement, network } = useGameStore((s) => ({
    hoveredElement: s.hoveredElement,
    network: s.network,
  }))

  if (!hoveredElement || !position || !network) return null
  if (hoveredElement.elementType !== 'LINE') return null

  const branch = network.branches.find((b) => b.id === hoveredElement.elementId)
  if (!branch) return null

  const pct = branch.loadingPercent

  return (
    <div
      className={`${styles.tooltip} ${borderClass(pct)}`}
      style={{ left: position.x, top: position.y }}
      data-testid="line-tooltip"
    >
      <div className={styles.name}>{branch.name || branch.id}</div>
      <div className={styles.row}>
        <span className={styles.rowLabel}>Loading</span>
        <span className={`${styles.rowValue} ${severityClass(pct)}`}>{fmt(pct)}%</span>
      </div>
      {branch.currentA != null && (
        <div className={styles.row}>
          <span className={styles.rowLabel}>Current</span>
          <span className={styles.rowValue}>{fmt(branch.currentA)} A</span>
        </div>
      )}
      {branch.ratingA != null && (
        <div className={styles.row}>
          <span className={styles.rowLabel}>Rated</span>
          <span className={styles.rowValue}>{fmt(branch.ratingA)} A</span>
        </div>
      )}
      <div className={styles.row}>
        <span className={styles.rowLabel}>Flow</span>
        <span className={styles.rowValue}>{fmt(Math.abs(branch.activePowerMw))} MW</span>
      </div>
    </div>
  )
}
