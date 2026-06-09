import { useCallback } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type { BranchDto, BusDto, GeneratorDto, GridNetworkDto, LoadDto, ViolationDto } from '../api/types'
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
    </>
  )
}

function BusCard({ id, network }: { id: string; network: GridNetworkDto }) {
  // substationId is used as the BUS element ID
  const buses = network.buses.filter((b) => b.substationId === id)
  const primary = buses[0] as BusDto | undefined
  if (!primary) return null
  const connectedBranches = network.branches.filter(
    (br) => buses.some((b) => b.id === br.fromBusId || b.id === br.toBusId),
  )
  return (
    <>
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
  BUS: '🔌 Substation',
  LOAD: '🏘 City',
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
          <span className={styles.title}>{TYPE_LABEL[elementType] ?? elementType} {elementId}</span>
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
