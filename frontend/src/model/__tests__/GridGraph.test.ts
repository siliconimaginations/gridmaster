import { describe, it, expect } from 'vitest'
import {
  voltageZone,
  networkDtoToGridGraph,
  updateGridGraph,
  V_CRIT_LOW,
  V_WARN_LOW,
  V_WARN_HIGH,
  V_CRIT_HIGH,
  VOLTAGE_COLORS,
} from '../GridGraph'
import type { GridNetworkDto, ViolationDto } from '../../api/types'

// ── Fixtures ──────────────────────────────────────────────────────────────────

const makeDto = (overrides: Partial<GridNetworkDto> = {}): GridNetworkDto => ({
  buses: [
    { id: 'B1', name: 'GenBus',  voltageKv: 220, voltagePu: 1.02, angleRad: 0, substationId: 'S1' },
    { id: 'B2', name: 'LoadBus', voltageKv: 110, voltagePu: 0.97, angleRad: 0, substationId: 'S1' },
    { id: 'B3', name: 'SubBus',  voltageKv: 110, voltagePu: null as unknown as number, angleRad: 0, substationId: 'S1' },
  ],
  generators: [
    { id: 'G1', busId: 'B1', name: 'Gen1', activePowerMw: 80, maxActivePowerMw: 120, committed: true,  fuelType: 'COAL', marginalCostPerMwh: 90.0 },
    { id: 'G2', busId: 'B1', name: 'Gen2', activePowerMw: 20, maxActivePowerMw:  80, committed: false, fuelType: 'GAS',  marginalCostPerMwh: 48.6 },
  ],
  loads: [
    { id: 'L1', busId: 'B2', name: 'Load1', activePowerMw: 50, reactivePowerMvar: 10 },
  ],
  branches: [
    { id: 'BR1', fromBusId: 'B1', toBusId: 'B2', activePowerMw: 60, reactivePowerMvar: 5, connected: true,  loadingPercent: 70  },
    { id: 'BR2', fromBusId: 'B2', toBusId: 'B3', activePowerMw: 10, reactivePowerMvar: 2, connected: false, loadingPercent: 110 },
  ],
  ...overrides,
})

// ── voltageZone ───────────────────────────────────────────────────────────────

describe('voltageZone', () => {
  it('below V_CRIT_LOW returns crit-low', () => {
    expect(voltageZone(V_CRIT_LOW - 0.01)).toBe('crit-low')
    expect(voltageZone(0.80)).toBe('crit-low')
  })

  it('between V_CRIT_LOW and V_WARN_LOW returns warn-low', () => {
    expect(voltageZone(V_CRIT_LOW)).toBe('warn-low')
    expect(voltageZone((V_CRIT_LOW + V_WARN_LOW) / 2)).toBe('warn-low')
  })

  it('between V_WARN_LOW and V_WARN_HIGH returns normal', () => {
    expect(voltageZone(1.0)).toBe('normal')
    expect(voltageZone(V_WARN_LOW)).toBe('normal')
    expect(voltageZone(V_WARN_HIGH)).toBe('normal')
  })

  it('between V_WARN_HIGH and V_CRIT_HIGH returns warn-high', () => {
    expect(voltageZone(V_WARN_HIGH + 0.01)).toBe('warn-high')
    expect(voltageZone((V_WARN_HIGH + V_CRIT_HIGH) / 2)).toBe('warn-high')
  })

  it('above V_CRIT_HIGH returns crit-high', () => {
    expect(voltageZone(V_CRIT_HIGH + 0.01)).toBe('crit-high')
    expect(voltageZone(1.20)).toBe('crit-high')
  })

  it('all 5 zones have distinct colors', () => {
    const colors = Object.values(VOLTAGE_COLORS)
    expect(new Set(colors).size).toBe(5)
  })
})

// ── networkDtoToGridGraph ─────────────────────────────────────────────────────

describe('networkDtoToGridGraph', () => {
  it('creates correct bus roles', () => {
    const g = networkDtoToGridGraph(makeDto())
    expect(g.buses.get('B1')!.role).toBe('gen')   // has generators with capacity
    expect(g.buses.get('B2')!.role).toBe('load')   // has loads, no gen capacity
    expect(g.buses.get('B3')!.role).toBe('sub')    // neither
  })

  it('defaults voltagePu to 1.0 when null', () => {
    const g = networkDtoToGridGraph(makeDto())
    expect(g.buses.get('B3')!.v).toBe(1.0)
  })

  it('sums only committed generator MW', () => {
    const g = networkDtoToGridGraph(makeDto())
    const bus = g.buses.get('B1')!
    expect(bus.genMw).toBe(80)          // G1 committed; G2 not
    expect(bus.genMaxMw).toBe(200)       // G1 120 + G2 80
  })

  it('sums load MW correctly', () => {
    const g = networkDtoToGridGraph(makeDto())
    expect(g.buses.get('B2')!.loadMw).toBe(50)
    expect(g.buses.get('B1')!.loadMw).toBe(0)
  })

  it('sets fuelType from the largest-capacity generator on the bus (#335)', () => {
    const g = networkDtoToGridGraph(makeDto())
    // G1 (COAL, 120 MW max) dominates G2 (GAS, 80 MW max)
    expect(g.buses.get('B1')!.fuelType).toBe('COAL')
    // Non-generator buses carry no fuel type
    expect(g.buses.get('B2')!.fuelType).toBeUndefined()
    expect(g.buses.get('B3')!.fuelType).toBeUndefined()
  })

  it('sets adjacency bidirectionally for connected branches', () => {
    const g = networkDtoToGridGraph(makeDto())
    expect(g.adjacency.get('B1')!.has('B2')).toBe(true)
    expect(g.adjacency.get('B2')!.has('B1')).toBe(true)
  })

  it('also adds adjacency for disconnected branches', () => {
    // BR2 is disconnected but adjacency is topology-based
    const g = networkDtoToGridGraph(makeDto())
    expect(g.adjacency.get('B2')!.has('B3')).toBe(true)
    expect(g.adjacency.get('B3')!.has('B2')).toBe(true)
  })

  it('computes edge loadFactor from loadingPercent', () => {
    const g = networkDtoToGridGraph(makeDto())
    const br1 = g.edges.find(e => e.id === 'BR1')!
    expect(br1.loadFactor).toBeCloseTo(0.70)
    expect(br1.isNearLimit).toBe(false)   // 70% < 85%
    expect(br1.isOverloaded).toBe(false)
  })

  it('flags isNearLimit and isOverloaded correctly', () => {
    const g = networkDtoToGridGraph(makeDto())
    const br2 = g.edges.find(e => e.id === 'BR2')!
    expect(br2.loadFactor).toBeCloseTo(1.10)
    expect(br2.isNearLimit).toBe(true)
    expect(br2.isOverloaded).toBe(true)
  })

  it('initialises bus canvas coords to zero', () => {
    const g = networkDtoToGridGraph(makeDto())
    for (const bus of g.buses.values()) {
      expect(bus.x).toBe(0)
      expect(bus.y).toBe(0)
    }
  })

  it('applies voltage violations to buses', () => {
    const violations: ViolationDto[] = [
      { elementId: 'B1', elementType: 'BUS', violationType: 'VOLTAGE_HIGH', value: 1.12, limit: 1.10 },
    ]
    const g = networkDtoToGridGraph(makeDto(), violations)
    expect(g.buses.get('B1')!.hasVoltageViolation).toBe(true)
    expect(g.buses.get('B1')!.violationType).toBe('VOLTAGE_HIGH')
    expect(g.buses.get('B2')!.hasVoltageViolation).toBe(false)
  })

  it('returns buses as Map keyed by id', () => {
    const g = networkDtoToGridGraph(makeDto())
    expect(g.buses).toBeInstanceOf(Map)
    expect(g.buses.size).toBe(3)
  })
})

// ── updateGridGraph ───────────────────────────────────────────────────────────

describe('updateGridGraph', () => {
  it('preserves x/y for buses that exist in both graphs', () => {
    const existing = networkDtoToGridGraph(makeDto())
    existing.buses.get('B1')!.x = 400
    existing.buses.get('B1')!.y = 300

    const updated = updateGridGraph(existing, makeDto())
    expect(updated.buses.get('B1')!.x).toBe(400)
    expect(updated.buses.get('B1')!.y).toBe(300)
  })

  it('initialises x/y to 0 for buses new in the update', () => {
    const existing = networkDtoToGridGraph(makeDto())
    const dto = makeDto({
      buses: [
        ...makeDto().buses,
        { id: 'B4', name: 'New', voltageKv: 110, voltagePu: 1.0, angleRad: 0, substationId: 'S1' },
      ],
    })
    const updated = updateGridGraph(existing, dto)
    expect(updated.buses.get('B4')!.x).toBe(0)
    expect(updated.buses.get('B4')!.y).toBe(0)
  })

  it('updates voltage and violation data from fresh DTO', () => {
    const existing = networkDtoToGridGraph(makeDto())
    const violations: ViolationDto[] = [
      { elementId: 'B2', elementType: 'BUS', violationType: 'VOLTAGE_LOW', value: 0.88, limit: 0.90 },
    ]
    const updated = updateGridGraph(existing, makeDto(), violations)
    expect(updated.buses.get('B2')!.hasVoltageViolation).toBe(true)
  })
})
