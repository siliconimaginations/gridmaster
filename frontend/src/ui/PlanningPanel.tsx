import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type { ViolationDto } from '../api/types'
import { useGameStore } from '../state/useGameStore'
import styles from './PlanningPanel.module.css'

// ── N-1 table ─────────────────────────────────────────────────────────────────

/**
 * Formats the violation severity as a loading percentage or pu voltage string.
 * @see ViolationDto
 */
function formatSeverity(v: ViolationDto): string {
  if (v.violationType === 'OVERLOAD') {
    const pct = v.limit > 0 ? Math.round((v.value / v.limit) * 100) : 0
    return `${pct}%`
  }
  return `${v.value.toFixed(2)} pu`
}

function severityClass(v: ViolationDto): string {
  if (v.violationType === 'OVERLOAD') {
    const pct = v.limit > 0 ? (v.value / v.limit) * 100 : 0
    if (pct >= 110) return styles.severityRed
    if (pct >= 95) return styles.severityAmber
    return styles.severityGreen
  }
  // Voltage
  const pct = v.limit > 0 ? (v.value / v.limit) * 100 : 100
  if (pct <= 90 || pct >= 110) return styles.severityRed
  if (pct <= 94 || pct >= 106) return styles.severityAmber
  return styles.severityGreen
}

function violationLabel(v: ViolationDto): string {
  switch (v.violationType) {
    case 'OVERLOAD':      return 'Overload'
    case 'VOLTAGE_HIGH':  return 'V high'
    case 'VOLTAGE_LOW':   return 'V low'
    default:              return v.violationType
  }
}

interface N1TableTabProps {
  violations: ViolationDto[]
}

function N1TableTab({ violations }: N1TableTabProps) {
  if (violations.length === 0) {
    return (
      <div className={styles.emptyState} data-testid="n1-empty">
        No active violations — system is N-1 secure.
      </div>
    )
  }

  // Sort by severity descending
  const sorted = [...violations].sort((a, b) => {
    const ra = a.limit > 0 ? a.value / a.limit : 0
    const rb = b.limit > 0 ? b.value / b.limit : 0
    return rb - ra
  })

  return (
    <div className={styles.tabContent} data-testid="n1-table">
      <div className={`${styles.n1Header} ${styles.n1Row}`}>
        <span>Element</span>
        <span>Type</span>
        <span>Violation</span>
        <span>Loading / V</span>
      </div>
      <div className={styles.n1List}>
        {sorted.map((v) => (
          <div
            key={`${v.elementId}-${v.violationType}`}
            className={`${styles.n1Row} ${severityClass(v)}`}
            data-testid={`n1-row-${v.elementId}`}
          >
            <span className={styles.n1ElementId} title={v.elementId}>
              {v.elementId}
            </span>
            <span className={styles.n1ElementType}>{v.elementType}</span>
            <span className={styles.n1ViolationType}>{violationLabel(v)}</span>
            <span className={styles.n1Severity}>{formatSeverity(v)}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── 7-day forecast tab ────────────────────────────────────────────────────────

/** Placeholder 7-day demand forecast. Stage 6 will wire this to real forecast data. */
const PLACEHOLDER_FORECAST: { day: string; demandMw: number; renewableMw: number }[] = [
  { day: 'Mon', demandMw: 4800, renewableMw: 1200 },
  { day: 'Tue', demandMw: 5100, renewableMw: 950 },
  { day: 'Wed', demandMw: 4900, renewableMw: 1400 },
  { day: 'Thu', demandMw: 5300, renewableMw: 800 },
  { day: 'Fri', demandMw: 5500, renewableMw: 1100 },
  { day: 'Sat', demandMw: 4200, renewableMw: 1600 },
  { day: 'Sun', demandMw: 3900, renewableMw: 1700 },
]

const MAX_FORECAST_MW = 6000

function ForecastTab() {
  return (
    <div className={styles.tabContent} data-testid="forecast-tab">
      <div className={styles.forecastLegend}>
        <span className={styles.legendDemand}>■ Peak demand</span>
        <span className={styles.legendRenewable}>■ Renewable output</span>
        <span className={styles.legendNote}>(placeholder — Stage 6 will connect live forecast)</span>
      </div>
      <div className={styles.forecastChart}>
        {PLACEHOLDER_FORECAST.map(({ day, demandMw, renewableMw }) => {
          const demandPct = (demandMw / MAX_FORECAST_MW) * 100
          const renewPct = (renewableMw / MAX_FORECAST_MW) * 100
          return (
            <div key={day} className={styles.forecastBar} data-testid={`forecast-bar-${day}`}>
              <div className={styles.forecastBarTrack}>
                <div
                  className={styles.forecastBarDemand}
                  style={{ height: `${demandPct}%` }}
                  title={`${demandMw} MW demand`}
                />
                <div
                  className={styles.forecastBarRenewable}
                  style={{ height: `${renewPct}%` }}
                  title={`${renewableMw} MW renewable`}
                />
              </div>
              <span className={styles.forecastBarLabel}>{day}</span>
            </div>
          )
        })}
      </div>
    </div>
  )
}

// ── Investment queue tab ──────────────────────────────────────────────────────

/** Placeholder investment options. Stage 6 (Free Play) will connect to real investment engine. */
const INVEST_OPTIONS = [
  { id: 'solar-b',   status: 'built',    name: 'Solar Farm B',      costM: 120, eta: '—',       capacity: '200 MW' },
  { id: 'ccgt-2',    status: 'building', name: 'CCGT-2',            costM: 200, eta: '18 days',  capacity: '600 MW' },
  { id: 'wind-x',    status: 'option',   name: 'Offshore Wind X',   costM: 350, eta: '24 days',  capacity: '400 MW' },
  { id: 'battery-1', status: 'option',   name: 'Battery Storage 1', costM: 80,  eta: '7 days',   capacity: '100 MW' },
  { id: 'line-l4',   status: 'option',   name: 'Line Upgrade L4',   costM: 60,  eta: '5 days',   capacity: '+50% L4' },
]

const STATUS_ICON: Record<string, string> = {
  built:    '✓',
  building: '⚙',
  option:   '💡',
}

function InvestTab() {
  return (
    <div className={styles.tabContent} data-testid="invest-tab">
      <div className={styles.budgetBar}>
        <span className={styles.budgetLabel}>Available budget</span>
        <span className={styles.budgetAmount}>£480M</span>
        <span className={styles.budgetNote}>(placeholder — Stage 6)</span>
      </div>
      <div className={`${styles.investHeader} ${styles.investRow}`}>
        <span>Status</span>
        <span>Project</span>
        <span>Cost</span>
        <span>Build time</span>
        <span>Capacity</span>
      </div>
      <div className={styles.investList}>
        {INVEST_OPTIONS.map((opt) => (
          <div
            key={opt.id}
            className={`${styles.investRow} ${opt.status === 'option' ? styles.investRowOption : ''}`}
            data-testid={`invest-row-${opt.id}`}
          >
            <span className={styles.investStatus} title={opt.status}>
              {STATUS_ICON[opt.status]}
            </span>
            <span className={styles.investName}>{opt.name}</span>
            <span className={styles.investCost}>£{opt.costM}M</span>
            <span className={styles.investEta}>{opt.eta}</span>
            <span className={styles.investCapacity}>{opt.capacity}</span>
          </div>
        ))}
      </div>
    </div>
  )
}

// ── Panel root ────────────────────────────────────────────────────────────────

type Tab = 'invest' | 'n1' | 'forecast'

interface PlanningPanelProps {
  /** Whether the panel is visible. */
  open: boolean
  /** Called when the player dismisses the panel. */
  onClose: () => void
}

/**
 * Planning Panel — investment queue, N-1 contingency table, 7-day demand forecast.
 *
 * Opened via the "Plan Day" button in BottomHud.
 * N-1 tab shows live violations from the Zustand store.
 * Invest + Forecast tabs use placeholder data until Stage 6 free-play engine.
 *
 * @see docs/ux/07-planning-panel.md
 * @see issue #89
 */
export function PlanningPanel({ open, onClose }: PlanningPanelProps) {
  const [activeTab, setActiveTab] = useState<Tab>('invest')

  const { violations } = useGameStore(
    useShallow((s) => ({ violations: s.violations })),
  )

  if (!open) return null

  return (
    <div className={styles.overlay} data-testid="planning-panel">
      {/* ── Header ── */}
      <div className={styles.header}>
        <span className={styles.title}>Planning</span>
        <div className={styles.tabs}>
          {([
            ['invest',   'Invest'],
            ['n1',       'N-1 Table'],
            ['forecast', 'Forecast'],
          ] as [Tab, string][]).map(([id, label]) => (
            <button
              key={id}
              className={`${styles.tab} ${activeTab === id ? styles.tabActive : ''}`}
              onClick={() => setActiveTab(id)}
              data-testid={`tab-${id}`}
            >
              {label}
            </button>
          ))}
        </div>
        <button className={styles.closeBtn} onClick={onClose} aria-label="Close planning panel" data-testid="btn-planning-close">
          ×
        </button>
      </div>

      {/* ── Tab body ── */}
      {activeTab === 'invest'   && <InvestTab />}
      {activeTab === 'n1'       && <N1TableTab violations={violations ?? []} />}
      {activeTab === 'forecast' && <ForecastTab />}
    </div>
  )
}
