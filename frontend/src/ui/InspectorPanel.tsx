import { useCallback, useEffect, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type {
  BranchDto,
  BusDto,
  ContingencyBranchResult,
  GeneratorDto,
  GridNetworkDto,
  LoadDto,
  ViolationDto,
} from '../api/types'
import { getContingencyForBranch } from '../api/restClient'
import { useGameStore } from '../state/useGameStore'
import styles from './InspectorPanel.module.css'

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmt(value: number, decimals = 1): string {
  return value.toFixed(decimals)
}

function loadingClass(pct: number): string {
  if (pct >= 100) return styles.valueCritical
  if (pct >= 85) return styles.valueWarning
  return ''
}

function voltageClass(pu: number): string {
  if (pu < 0.95 || pu > 1.05) return styles.valueCritical
  if (pu < 0.97 || pu > 1.03) return styles.valueWarning
  return ''
}

// ── Card sub-components ───────────────────────────────────────────────────────

function Row({ label, value, cls = '' }: { label: string; value: string; cls?: string }) {
  return (
    <div className={styles.row}>
      <span className={styles.rowLabel}>{label}</span>
      <span className={`${styles.rowValue} ${cls}`}>{value}</span>
    </div>
  )
}

function GeneratorCard({ id, network }: { id: string; network: GridNetworkDto }) {
  const gen = network.generators.find((g) => g.id === id) as GeneratorDto | undefined
  if (!gen) return null
  return (
    <>
      <Row label="Output" value={`${fmt(gen.activePowerMw)} MW`} />
      <Row label="Max" value={`${fmt(gen.maxActivePowerMw)} MW`} />
      <Row
        label="Loading"
        value={`${fmt(gen.maxActivePowerMw > 0 ? (gen.activePowerMw / gen.maxActivePowerMw) * 100 : 0)}%`}
        cls={loadingClass(gen.maxActivePowerMw > 0 ? (gen.activePowerMw / gen.maxActivePowerMw) * 100 : 0)}
      />
      <Row label="Fuel" value={gen.fuelType} />
      <Row label="Status" value={gen.committed ? 'Committed' : 'Decommitted'} />
    </>
  )
}

/**
 * Collapsible "N-1 Security" section for the line inspector card.
 *
 * Shows the post-contingency impact of losing this line, fetched from
 * GET /contingency/{branchId} on mount: a status chip (secure / violation
 * count / non-convergence) plus a per-violation breakdown when expanded.
 * Renders a neutral "No analysis" chip until an N-1 run has completed.
 */
function N1SecuritySection({ branchId }: { branchId: string }) {
  const sessionId = useGameStore((s) => s.sessionId)
  const [data, setData] = useState<ContingencyBranchResult | null>(null)
  const [loaded, setLoaded] = useState(false)
  const [expanded, setExpanded] = useState(false)

  useEffect(() => {
    if (!sessionId) return
    let cancelled = false

    getContingencyForBranch(sessionId, branchId)
      .then((result) => {
        if (!cancelled) {
          setData(result)
          setLoaded(true)
        }
      })
      .catch(() => {
        if (!cancelled) setLoaded(true)
      })

    return () => {
      cancelled = true
    }
  }, [sessionId, branchId])

  let chip = <span className={styles.n1ChipNeutral}>…</span>
  if (loaded) {
    if (!data) {
      chip = <span className={styles.n1ChipNeutral}>No analysis</span>
    } else if (data.status === 'SECURE') {
      chip = <span className={styles.n1ChipSecure}>Secure ✓</span>
    } else if (data.status === 'VIOLATION') {
      chip = (
        <span className={styles.n1ChipViolation}>
          {data.violations.length} violation{data.violations.length === 1 ? '' : 's'}
        </span>
      )
    } else {
      chip = <span className={styles.n1ChipViolation}>Would not converge</span>
    }
  }

  return (
    <div className={styles.n1Section} data-testid="n1-security-section">
      <button
        type="button"
        className={styles.n1Header}
        data-testid="n1-toggle"
        onClick={() => setExpanded((e) => !e)}
      >
        {expanded ? '▾' : '▸'} N-1 Security {chip}
      </button>

      {expanded && data && data.violations.length > 0 && (
        <div>
          {data.violations.map((v) => (
            <div key={`${v.equipmentId}-${v.violationType}`}>
              <Row
                label={v.equipmentId}
                value={`${v.violationType} ${fmt(v.loadingPercent)}%`}
                cls={styles.valueCritical}
              />
            </div>
          ))}
        </div>
      )}

      {expanded && data && data.status === 'SECURE' && (
        <Row label="If this line trips" value="No violations" />
      )}

      {expanded && !data && loaded && <Row label="Analysis" value="Not yet run" />}
    </div>
  )
}

function LineCard({ id, network }: { id: string; network: GridNetworkDto }) {
  const branch = network.branches.find((b) => b.id === id) as BranchDto | undefined
  if (!branch) return null
  const fromBus = network.buses.find((b) => b.id === branch.fromBusId) as BusDto | undefined
  const toBus = network.buses.find((b) => b.id === branch.toBusId) as BusDto | undefined
  return (
    <>
      <Row
        label="Loading"
        value={`${fmt(branch.loadingPercent)}%`}
        cls={loadingClass(branch.loadingPercent)}
      />
      <Row label="Flow" value={`${fmt(Math.abs(branch.activePowerMw))} MW`} />
      <Row label="From" value={fromBus?.name ?? branch.fromBusId} />
      <Row label="To" value={toBus?.name ?? branch.toBusId} />
      <Row label="Status" value={branch.connected ? 'Connected' : 'Tripped'} />
      <N1SecuritySection branchId={id} />
    </>
  )
}

type BusRole = 'gen' | 'load' | 'sub'

interface BusStats {
  role: BusRole
  genMw: number
  genMaxMw: number
  loadMw: number
}

/**
 * Derived bus role + aggregate generation/load figures, mirroring
 * model/GridGraph.ts's BusRole logic: 'gen' if the bus hosts any generator
 * capacity, 'load' if it hosts load demand, 'sub' otherwise. Recomputed here
 * (rather than threaded in from GridGraph) because InspectorPanel only has
 * the raw GridNetworkDto, not the renderer's graph. Single pass over each
 * array — role and metrics are derived together so callers (header label,
 * BusCard body) don't each re-filter the same arrays (#370 Gemini review).
 */
function busStats(id: string, network: GridNetworkDto): BusStats {
  let genMw = 0
  let genMaxMw = 0
  for (const g of network.generators) {
    if (g.busId !== id) continue
    genMw += g.activePowerMw
    genMaxMw += g.maxActivePowerMw
  }
  let loadMw = 0
  for (const l of network.loads) {
    if (l.busId !== id) continue
    loadMw += l.activePowerMw
  }
  const role: BusRole = genMaxMw > 0 ? 'gen' : loadMw > 0 ? 'load' : 'sub'
  return { role, genMw, genMaxMw, loadMw }
}

function BusCard({ id, network }: { id: string; network: GridNetworkDto }) {
  // The clicked element's id is the bus's own id (matches BusDto.id — see
  // BusNode.id in model/GridGraph.ts), NOT substationId. Filtering by
  // `substationId === id` never matched anything (substationId is a
  // different, often-null grouping field), so this card always rendered
  // empty (#364).
  const primary = network.buses.find((b) => b.id === id) as BusDto | undefined
  if (!primary) return null
  // Other buses sharing this one's substation, for a "voltage levels here"
  // count — falls back to just this bus when it has no substation grouping.
  const buses = primary.substationId
    ? network.buses.filter((b) => b.substationId === primary.substationId)
    : [primary]
  const connectedBranches = network.branches.filter(
    (br) => br.fromBusId === id || br.toBusId === id,
  )

  // Type-specific state (#370): every bus previously showed only the generic
  // voltage/topology rows and was always labelled "Substation" regardless of
  // what's actually attached to it. Show generation/load figures for buses
  // that host generators or loads, in addition to the shared rows.
  const { role, genMw, genMaxMw, loadMw } = busStats(id, network)

  return (
    <>
      {role === 'gen' && (
        <>
          <Row label="Generation" value={`${fmt(genMw)} MW`} />
          <Row label="Capacity" value={`${fmt(genMaxMw)} MW`} />
          <Row
            label="Loading"
            value={`${fmt(genMaxMw > 0 ? (genMw / genMaxMw) * 100 : 0)}%`}
            cls={loadingClass(genMaxMw > 0 ? (genMw / genMaxMw) * 100 : 0)}
          />
        </>
      )}
      {role === 'load' && <Row label="Demand" value={`${fmt(loadMw)} MW`} />}
      <Row
        label="Voltage"
        value={`${fmt(primary.voltagePu, 3)} pu`}
        cls={voltageClass(primary.voltagePu)}
      />
      <Row label="Base kV" value={`${fmt(primary.voltageKv, 0)} kV`} />
      <Row label="Buses" value={String(buses.length)} />
      <Row label="Lines" value={String(connectedBranches.length)} />
    </>
  )
}

function LoadCard({ id, network }: { id: string; network: GridNetworkDto }) {
  const load = network.loads.find((l) => l.id === id) as LoadDto | undefined
  if (!load) return null
  return (
    <>
      <Row label="Demand" value={`${fmt(load.activePowerMw)} MW`} />
      <Row label="Reactive" value={`${fmt(load.reactivePowerMvar)} Mvar`} />
      <Row label="Status" value="Supplied ✓" />
    </>
  )
}

function elementHasViolation(id: string, elementType: string, violations: ViolationDto[]): boolean {
  return violations.some((v) => {
    if (elementType === 'BUS') return v.elementId === id
    return v.elementId === id
  })
}

// ── InspectorPanel ────────────────────────────────────────────────────────────

const TYPE_LABEL: Record<string, string> = {
  GENERATOR: '⚡ Generator',
  LINE: '〰 Line',
  LOAD: '🏘 City',
}

/** Header labels for BUS elements, keyed by the derived {@link BusRole} (#370). */
const BUS_ROLE_LABEL: Record<BusRole, string> = {
  gen: '⚡ Generator',
  load: '🏘 City',
  sub: '🔌 Substation',
}

/**
 * Floating popup card shown when the player clicks a grid element in the scene.
 *
 * Reads `selectedElement` from the Zustand store. Returns null when nothing is
 * selected. Clicking the backdrop clears the selection.
 *
 * @see docs/ux/02-component-inspector.md
 * @see issue #86
 */
export function InspectorPanel() {
  const { selectedElement, network, violations } = useGameStore(
    useShallow((s) => ({
      selectedElement: s.selectedElement,
      network: s.network,
      violations: s.violations,
    })),
  )

  const close = useCallback(() => {
    useGameStore.getState().selectElement(null)
  }, [])

  if (!selectedElement || !network) return null

  const { elementType, elementId } = selectedElement
  const hasViolation = elementHasViolation(elementId, elementType, violations)
  const headerLabel =
    elementType === 'BUS'
      ? BUS_ROLE_LABEL[busStats(elementId, network).role]
      : (TYPE_LABEL[elementType] ?? elementType)

  return (
    <>
      {/* Invisible backdrop — click-away closes the inspector */}
      <div
        className={styles.backdrop}
        onClick={close}
        data-testid="inspector-backdrop"
      />
      <div
        className={`${styles.panel} ${hasViolation ? styles.panelViolation : ''}`}
        data-testid="inspector-panel"
      >
        {/* Header */}
        <div className={styles.header}>
          <span className={styles.title}>{headerLabel} {elementId}</span>
          <button className={styles.closeBtn} onClick={close} aria-label="Close inspector">×</button>
        </div>

        {/* Metric rows */}
        <div className={styles.body}>
          {elementType === 'GENERATOR' && <GeneratorCard id={elementId} network={network} />}
          {elementType === 'LINE' && <LineCard id={elementId} network={network} />}
          {elementType === 'BUS' && <BusCard id={elementId} network={network} />}
          {elementType === 'LOAD' && <LoadCard id={elementId} network={network} />}
        </div>
      </div>
    </>
  )
}
