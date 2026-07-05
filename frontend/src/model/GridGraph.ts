/**
 * GridGraph — canonical in-memory model for the power grid topology.
 *
 * Replaces the ad-hoc flat arrays previously used in scene code.
 * Converted from server DTOs via {@link networkDtoToGridGraph}.
 * Layout coordinates (x, y) are populated separately by the layout algorithm.
 *
 * See docs/engineering/15-pixi-renderer.md §Data model.
 */

import type { GridNetworkDto, ViolationDto } from '../api/types'

// ── Voltage zone thresholds (per-unit) ────────────────────────────────────────

export const V_CRIT_LOW  = 0.90
export const V_WARN_LOW  = 0.95
export const V_WARN_HIGH = 1.05
export const V_CRIT_HIGH = 1.10

export type VoltageZone = 'crit-low' | 'warn-low' | 'normal' | 'warn-high' | 'crit-high'

/** 5-zone voltage color — symmetric around normal. */
export const VOLTAGE_COLORS: Record<VoltageZone, number> = {
  'crit-low':  0xbb66ff,
  'warn-low':  0x4488ff,
  'normal':    0x28cc60,
  'warn-high': 0xff8822,
  'crit-high': 0xff3030,
}

export function voltageZone(v: number): VoltageZone {
  if (v < V_CRIT_LOW)  return 'crit-low'
  if (v < V_WARN_LOW)  return 'warn-low'
  if (v > V_CRIT_HIGH) return 'crit-high'
  if (v > V_WARN_HIGH) return 'warn-high'
  return 'normal'
}

// ── Node types ─────────────────────────────────────────────────────────────────

/** Derived bus role: 'gen' if it has committed generators, 'load' if it has loads, 'sub' otherwise. */
export type BusRole = 'gen' | 'sub' | 'load'

export interface BusNode {
  /** Matches BusDto.id */
  id: string
  role: BusRole
  name: string

  /** Nominal voltage in kV — used for LOD grouping and display. */
  voltageKv: number

  /** Per-unit voltage from power flow solution. */
  v: number

  /** Total generation committed on this bus (MW). 0 for non-generator buses. */
  genMw: number
  /** Max generation capacity on this bus (MW). */
  genMaxMw: number
  /**
   * Fuel type of the largest-capacity generator on this bus (e.g. "GAS",
   * "NUCLEAR"); undefined for buses without generators. Drives the fuel
   * sub-icon in the PixiJS renderer (#335).
   */
  fuelType?: string

  /** Total load demand on this bus (MW). 0 for non-load buses. */
  loadMw: number

  /** Violation flags (derived from ViolationDto). */
  hasVoltageViolation: boolean
  violationType?: 'VOLTAGE_HIGH' | 'VOLTAGE_LOW'

  /**
   * Canvas coordinates — set by {@link layoutGrid}, zero until layout runs.
   * Updated on each viewport resize.
   */
  x: number
  y: number

  /** Geographic coordinates when available from the backend (decimal degrees). */
  lat?: number
  lon?: number
}

export interface BranchEdge {
  id: string
  fromId: string
  toId: string

  /** Loading as fraction of thermal rating (0–1+, >1 = overloaded). */
  loadFactor: number

  /** True if the branch is connected (not tripped). */
  connected: boolean

  /** True if loading > 85% of thermal rating. */
  isNearLimit: boolean

  /** True if loading > 100% of thermal rating. */
  isOverloaded: boolean
}

export interface GridGraph {
  buses: Map<string, BusNode>
  edges: BranchEdge[]

  /** O(1) adjacency: busId → Set of connected busIds. */
  adjacency: Map<string, Set<string>>
}

// ── Conversion from server DTOs ────────────────────────────────────────────────

/**
 * Converts a {@link GridNetworkDto} + violations array into a {@link GridGraph}.
 *
 * Canvas coordinates are initialised to (0, 0) — call {@link layoutGrid} afterwards.
 */
export function networkDtoToGridGraph(
  dto: GridNetworkDto,
  violations: ViolationDto[] = [],
): GridGraph {
  const buses = new Map<string, BusNode>()
  const adjacency = new Map<string, Set<string>>()

  // Index violations by element ID for O(1) lookup
  const busViolations = new Map<string, ViolationDto>()
  for (const v of violations) {
    if (v.elementType === 'BUS') busViolations.set(v.elementId, v)
  }

  // Index generators and loads by busId
  const gensByBus = groupBy(dto.generators, g => g.busId)
  const loadsByBus = groupBy(dto.loads, l => l.busId)

  for (const bus of dto.buses) {
    const gens  = gensByBus.get(bus.id) ?? []
    const loads = loadsByBus.get(bus.id) ?? []
    const viol  = busViolations.get(bus.id)

    const genMw    = gens.filter(g => g.committed).reduce((s, g) => s + g.activePowerMw, 0)
    const genMaxMw = gens.reduce((s, g) => s + g.maxActivePowerMw, 0)
    const loadMw   = loads.reduce((s, l) => s + l.activePowerMw, 0)

    const role: BusRole = genMaxMw > 0 ? 'gen' : loadMw > 0 ? 'load' : 'sub'

    // Dominant fuel = fuel of the largest-capacity generator on the bus (#335)
    let dominantFuel: string | undefined
    let dominantMax = -Infinity
    for (const g of gens) {
      if (g.maxActivePowerMw > dominantMax) {
        dominantMax = g.maxActivePowerMw
        dominantFuel = g.fuelType
      }
    }

    buses.set(bus.id, {
      id:        bus.id,
      role,
      name:      bus.name,
      voltageKv: bus.voltageKv ?? 0,
      v:         bus.voltagePu ?? 1.0,   // default to nominal if power flow hasn't converged
      genMw,
      genMaxMw,
      fuelType:  dominantFuel,
      loadMw,
      hasVoltageViolation: viol !== undefined,
      violationType: viol ? (viol.violationType as 'VOLTAGE_HIGH' | 'VOLTAGE_LOW') : undefined,
      x: 0,
      y: 0,
    })

    adjacency.set(bus.id, new Set())
  }

  // Build edges and adjacency
  const edges: BranchEdge[] = dto.branches.map(branch => {
    const loadFactor = branch.loadingPercent / 100

    // Populate adjacency both directions
    adjacency.get(branch.fromBusId)?.add(branch.toBusId)
    adjacency.get(branch.toBusId)?.add(branch.fromBusId)

    return {
      id:          branch.id,
      fromId:      branch.fromBusId,
      toId:        branch.toBusId,
      loadFactor,
      connected:   branch.connected,
      isNearLimit: loadFactor > 0.85,
      isOverloaded: loadFactor > 1.0,
    }
  })

  return { buses, edges, adjacency }
}

/**
 * Applies an updated {@link GridNetworkDto} + violations to an existing {@link GridGraph},
 * preserving layout coordinates (x, y) so the viewport doesn't jump.
 */
export function updateGridGraph(
  existing: GridGraph,
  dto: GridNetworkDto,
  violations: ViolationDto[] = [],
): GridGraph {
  const fresh = networkDtoToGridGraph(dto, violations)

  // Carry over layout coordinates for buses that persist
  for (const [id, node] of fresh.buses) {
    const prev = existing.buses.get(id)
    if (prev) {
      node.x = prev.x
      node.y = prev.y
    }
  }

  return fresh
}

// ── Utilities ──────────────────────────────────────────────────────────────────

function groupBy<T>(arr: T[], key: (item: T) => string): Map<string, T[]> {
  const map = new Map<string, T[]>()
  for (const item of arr) {
    const k = key(item)
    const existing = map.get(k)
    if (existing) existing.push(item)
    else map.set(k, [item])
  }
  return map
}
