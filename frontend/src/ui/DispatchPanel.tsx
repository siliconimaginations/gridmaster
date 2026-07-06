import { useCallback, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type { GeneratorDto } from '../api/types'
import { useGameStore } from '../state/useGameStore'
import styles from './DispatchPanel.module.css'

// ── Fuel type helpers ─────────────────────────────────────────────────────────

const FUEL_EMOJI: Record<string, string> = {
  SOLAR: '☀️',
  WIND: '💨',
  HYDRO: '💧',
  NUCLEAR: '⚛️',
  GAS: '🔥',
  COAL: '⚫',
  OIL: '🛢️',
}

/**
 * Fallback marginal cost (£/MWh) derived from fuel type, used only when the
 * server hasn't supplied a real per-generator `marginalCostPerMwh` (e.g. an
 * unmapped/OTHER fuel type, which defaults to 0 backend-side). As of #336 the
 * backend supplies real per-generator costs (derived from the MATPOWER
 * case14 generator cost curves) — see costGbpMwh() below, which is what the
 * rest of this component should call instead of this heuristic directly.
 */
function estimatedCostGbpMwh(fuelType: string): number {
  switch (fuelType.toUpperCase()) {
    case 'SOLAR': return 0
    case 'WIND':  return 4
    case 'HYDRO': return 12
    case 'NUCLEAR': return 14
    case 'GAS':   return 48
    case 'COAL':  return 55
    case 'OIL':   return 89
    default:      return 99
  }
}

/** Real per-generator marginal cost (£/MWh), falling back to the fuel-type heuristic if unset (#336). */
function costGbpMwh(gen: GeneratorDto): number {
  return gen.marginalCostPerMwh > 0 ? gen.marginalCostPerMwh : estimatedCostGbpMwh(gen.fuelType)
}

function fuelEmoji(fuelType: string): string {
  return FUEL_EMOJI[fuelType.toUpperCase()] ?? '⚡'
}

/** Bar colour per fuel type for the cost-stack chart (#336). */
const FUEL_COLOR: Record<string, string> = {
  SOLAR: '#f6c945',
  WIND: '#38bdf8',
  HYDRO: '#0ea5e9',
  NUCLEAR: '#a78bfa',
  GAS: '#fb923c',
  COAL: '#78716c',
  OIL: '#7c2d12',
}

function fuelColor(fuelType: string): string {
  return FUEL_COLOR[fuelType.toUpperCase()] ?? '#64748b'
}

// ── Real-time tab ─────────────────────────────────────────────────────────────

interface MeritOrderRowProps {
  rank: number
  gen: GeneratorDto
  expanded: boolean
  onToggleExpand: (id: string) => void
  onSetOutput: (id: string, mw: number) => void
  onToggleCommit: (id: string, committed: boolean) => void
}

function MeritOrderRow({ rank, gen, expanded, onToggleExpand, onSetOutput, onToggleCommit }: MeritOrderRowProps) {
  const pct = gen.maxActivePowerMw > 0 ? (gen.activePowerMw / gen.maxActivePowerMw) * 100 : 0
  const headroom = gen.maxActivePowerMw - gen.activePowerMw
  const rowClass = `${styles.genRow} ${!gen.committed ? styles.genRowDecommitted : ''}`

  return (
    <>
      <div
        className={rowClass}
        onClick={() => onToggleExpand(gen.id)}
        data-testid={`dispatch-row-${gen.id}`}
        role="button"
        aria-expanded={expanded}
      >
        <span className={styles.rank}>{rank}</span>
        <span className={styles.genName}>{gen.name}</span>
        <span className={styles.genFuel}>{fuelEmoji(gen.fuelType)}</span>
        <span className={styles.genCost}>£{costGbpMwh(gen).toFixed(1)}/MWh</span>
        <div className={styles.outputCell}>
          <div className={styles.outputBar}>
            <div className={styles.outputFill} style={{ width: `${Math.min(pct, 100)}%` }} />
          </div>
          <span className={styles.outputMw}>{Math.round(gen.activePowerMw)} MW</span>
        </div>
        <span className={styles.headroom} title="Available headroom">
          +{Math.round(headroom)} MW
        </span>
        <button
          className={`${styles.commitBtn} ${gen.committed ? styles.commitBtnOn : styles.commitBtnOff}`}
          onClick={(e) => { e.stopPropagation(); onToggleCommit(gen.id, gen.committed) }}
          data-testid={`commit-btn-${gen.id}`}
          aria-label={gen.committed ? 'Decommit generator' : 'Commit generator'}
        >
          {gen.committed ? 'ON' : 'OFF'}
        </button>
      </div>
      {expanded && gen.committed && (
        <div className={styles.sliderRow} data-testid={`slider-row-${gen.id}`}>
          <label className={styles.sliderLabel}>Set output</label>
          <input
            type="range"
            min={0}
            max={gen.maxActivePowerMw}
            step={1}
            defaultValue={gen.activePowerMw}
            className={styles.slider}
            onMouseUp={(e) => onSetOutput(gen.id, Number((e.target as HTMLInputElement).value))}
            onTouchEnd={(e) => onSetOutput(gen.id, Number((e.target as HTMLInputElement).value))}
            aria-label={`Output for ${gen.name}`}
          />
          <span className={styles.sliderMax}>{Math.round(gen.maxActivePowerMw)} MW</span>
        </div>
      )}
    </>
  )
}

function MeritOrderTab({ generators }: { generators: GeneratorDto[] }) {
  const [expandedId, setExpandedId] = useState<string | null>(null)
  const sendCommandOptimistic = useGameStore((s) => s.sendCommandOptimistic)
  const network = useGameStore((s) => s.network)

  const sorted = [...generators].sort((a, b) => {
    if (a.committed !== b.committed) return a.committed ? -1 : 1
    return costGbpMwh(a) - costGbpMwh(b)
  })

  const totalOutput = generators.filter((g) => g.committed).reduce((sum, g) => sum + g.activePowerMw, 0)
  const totalDemand = network?.loads.reduce((sum, l) => sum + l.activePowerMw, 0) ?? 0

  const handleToggleExpand = useCallback((id: string) => {
    setExpandedId((prev) => (prev === id ? null : id))
  }, [])

  const handleSetOutput = useCallback((id: string, mw: number) => {
    sendCommandOptimistic(
      { commandType: 'SetGeneratorOutput', payload: { generatorId: id, targetMw: mw } },
      (prev) => ({
        ...prev,
        generators: prev.generators.map((g) => (g.id === id ? { ...g, activePowerMw: mw } : g)),
      }),
    )
  }, [sendCommandOptimistic])

  const handleToggleCommit = useCallback((id: string, currentlyCommitted: boolean) => {
    const commandType = currentlyCommitted ? 'DecommitGenerator' : 'CommitGenerator'
    sendCommandOptimistic(
      { commandType, payload: { generatorId: id } },
      (prev) => ({
        ...prev,
        generators: prev.generators.map((g) =>
          g.id === id ? { ...g, committed: !currentlyCommitted } : g,
        ),
      }),
    )
    setExpandedId(null)
  }, [sendCommandOptimistic])

  return (
    <div className={styles.tabContent}>
      <div className={styles.tableHeader}>
        <span className={styles.rank}>#</span>
        <span className={styles.genName}>Generator</span>
        <span className={styles.genFuel}></span>
        <span className={styles.genCost}>Cost</span>
        <span className={styles.outputCell}>Output</span>
        <span className={styles.headroom}>Headroom</span>
        <span className={styles.commitBtn}></span>
      </div>
      <div className={styles.genList}>
        {sorted.map((gen, i) => (
          <MeritOrderRow
            key={gen.id}
            rank={i + 1}
            gen={gen}
            expanded={expandedId === gen.id}
            onToggleExpand={handleToggleExpand}
            onSetOutput={handleSetOutput}
            onToggleCommit={handleToggleCommit}
          />
        ))}
      </div>
      <div className={styles.totalRow}>
        Total: {Math.round(totalOutput)} MW / {Math.round(totalDemand)} MW demand
      </div>
    </div>
  )
}

// ── Day-ahead tab ─────────────────────────────────────────────────────────────

const HOURS = Array.from({ length: 24 }, (_, i) => i)

function UCScheduleTab({ generators }: { generators: GeneratorDto[] }) {
  const sendCommand = useGameStore((s) => s.sendCommand)
  const setUcSchedule = useGameStore((s) => s.setUcSchedule)

  // Local schedule state: generatorId → committed hours set
  const [schedule, setSchedule] = useState<Record<string, Set<number>>>(() =>
    Object.fromEntries(generators.map((g) => [g.id, new Set(g.committed ? HOURS : [])]))
  )

  function toggleCell(genId: string, hour: number) {
    setSchedule((prev) => {
      const next = new Map(Object.entries(prev).map(([k, v]) => [k, new Set(v)]))
      const hours = next.get(genId)!
      if (hours.has(hour)) hours.delete(hour); else hours.add(hour)
      return Object.fromEntries(next.entries())
    })
  }

  function autoFill() {
    const meritOrder = [...generators].sort((a, b) => costGbpMwh(a) - costGbpMwh(b))
    setSchedule((prev) => {
      const next = { ...prev }
      for (const gen of meritOrder) {
        if (!next[gen.id]) next[gen.id] = new Set()
        for (const h of HOURS) next[gen.id].add(h)
      }
      return next
    })
  }

  function confirm() {
    const hourlyForecastMw = HOURS.map(() => 1000) // placeholder forecast
    sendCommand({ commandType: 'RunUnitCommitment', payload: { hourlyForecastMw } })
    // Derive a 24-element boolean array: hour h is committed if any generator has h in its set.
    const committed = HOURS.map((h) =>
      Object.values(schedule).some((hourSet) => hourSet.has(h)),
    )
    setUcSchedule(committed)
  }

  const committed = generators.filter((g) => g.committed)

  return (
    <div className={styles.tabContent}>
      <div className={styles.ucGrid} data-testid="uc-grid">
        {/* Hour header row */}
        <div className={styles.ucRow}>
          <div className={styles.ucGenLabel} />
          {HOURS.map((h) => (
            <div key={h} className={styles.ucHourHeader}>{h}</div>
          ))}
        </div>
        {/* Generator rows */}
        {committed.map((gen) => (
          <div key={gen.id} className={styles.ucRow} data-testid={`uc-row-${gen.id}`}>
            <div className={styles.ucGenLabel} title={gen.name}>{gen.name}</div>
            {HOURS.map((h) => {
              const on = schedule[gen.id]?.has(h) ?? false
              return (
                <div
                  key={h}
                  className={`${styles.ucCell} ${on ? styles.ucCellOn : ''}`}
                  onClick={() => toggleCell(gen.id, h)}
                  data-testid={`uc-cell-${gen.id}-${h}`}
                  aria-label={`${gen.name} hour ${h}: ${on ? 'ON' : 'OFF'}`}
                  role="button"
                />
              )
            })}
          </div>
        ))}
      </div>
      <div className={styles.ucActions}>
        <button className={styles.autoFillBtn} onClick={autoFill} data-testid="btn-autofill">
          Auto-fill
        </button>
        <button className={styles.confirmBtn} onClick={confirm} data-testid="btn-uc-confirm">
          Confirm schedule
        </button>
      </div>
    </div>
  )
}

// ── Cost stack chart (issue #336) ─────────────────────────────────────────────

/**
 * Collapsible bar chart of generators sorted by marginal cost, coloured by
 * fuel type, with a vertical marker at the system marginal cost (SMC) — the
 * cost of the last (most expensive) unit needed to meet demand. Pure CSS
 * bars, no chart library, per issue #336's acceptance criteria.
 */
function CostStackSection({
  generators,
  systemMarginalCostPerMwh,
}: {
  generators: GeneratorDto[]
  systemMarginalCostPerMwh: number | null | undefined
}) {
  const [expanded, setExpanded] = useState(true)

  if (generators.length === 0) return null

  const sorted = [...generators].sort((a, b) => costGbpMwh(a) - costGbpMwh(b))
  const smc = typeof systemMarginalCostPerMwh === 'number' ? systemMarginalCostPerMwh : null
  const maxCost = Math.max(...sorted.map(costGbpMwh), smc ?? 0, 1)
  const smcPct = smc !== null ? Math.min((smc / maxCost) * 100, 100) : null

  return (
    <div className={styles.costStack} data-testid="cost-stack-section">
      <button
        type="button"
        className={styles.costStackHeader}
        onClick={() => setExpanded((e) => !e)}
        data-testid="cost-stack-toggle"
      >
        {expanded ? '▾' : '▸'} Cost stack {smc !== null ? `— SMC £${smc.toFixed(1)}/MWh` : ''}
      </button>

      {expanded && (
        <div className={styles.costStackBody} data-testid="cost-stack-chart">
          {sorted.map((gen) => {
            const cost = costGbpMwh(gen)
            const pct = Math.min((cost / maxCost) * 100, 100)
            return (
              <div key={gen.id} className={styles.costStackRow} data-testid={`cost-stack-bar-${gen.id}`}>
                <span className={styles.costStackLabel} title={gen.name}>{gen.name}</span>
                <div className={styles.costStackTrack}>
                  <div
                    className={styles.costStackBar}
                    style={{ width: `${pct}%`, background: fuelColor(gen.fuelType) }}
                  />
                  {smcPct !== null && (
                    <div
                      className={styles.costStackSmcLine}
                      style={{ left: `${smcPct}%` }}
                      data-testid="cost-stack-smc-line"
                    />
                  )}
                </div>
                <span className={styles.costStackValue}>£{cost.toFixed(1)}</span>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}

// ── DispatchPanel ─────────────────────────────────────────────────────────────

type Tab = 'realtime' | 'dayahead'

/**
 * Slide-up panel showing generator dispatch controls.
 *
 * Real-time tab: merit order table with output sliders and commit toggles.
 * Day-ahead tab: 24-hour unit commitment grid.
 *
 * Opened via the "Run Dispatch" button in BottomHud.
 * Reads network state from the Zustand store; sends commands over WebSocket.
 *
 * @see docs/ux/06-dispatch-panel.md
 * @see issue #88
 */
export function DispatchPanel({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [activeTab, setActiveTab] = useState<Tab>('realtime')
  const { network } = useGameStore(useShallow((s) => ({ network: s.network })))

  if (!open || !network) return null

  const generators = network.generators

  return (
    <div className={styles.overlay} data-testid="dispatch-panel">
      {/* Header */}
      <div className={styles.header}>
        <span className={styles.title}>Dispatch</span>
        <div className={styles.tabs} role="tablist">
          <button
            className={`${styles.tab} ${activeTab === 'realtime' ? styles.tabActive : ''}`}
            onClick={() => setActiveTab('realtime')}
            role="tab"
            aria-selected={activeTab === 'realtime'}
            data-testid="tab-realtime"
          >
            Real-time
          </button>
          <button
            className={`${styles.tab} ${activeTab === 'dayahead' ? styles.tabActive : ''}`}
            onClick={() => setActiveTab('dayahead')}
            role="tab"
            aria-selected={activeTab === 'dayahead'}
            data-testid="tab-dayahead"
          >
            Day-ahead
          </button>
        </div>
        <button className={styles.closeBtn} onClick={onClose} aria-label="Close dispatch panel">×</button>
      </div>

      <CostStackSection
        generators={generators}
        systemMarginalCostPerMwh={network.systemMarginalCostPerMwh}
      />

      {/* Tab content */}
      {activeTab === 'realtime'
        ? <MeritOrderTab generators={generators} />
        : <UCScheduleTab generators={generators} />}
    </div>
  )
}
