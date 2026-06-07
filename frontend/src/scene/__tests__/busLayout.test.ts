import { describe, expect, it } from 'vitest'
import type { BusDto } from '../../api/types'
import { layoutBuses } from '../layout/busLayout'
import { IEEE14_BUS_POSITIONS } from '../layout/ieee14Layout'
import { gridLayout } from '../layout/gridLayout'

// ── ieee14Layout ──────────────────────────────────────────────────────────────

describe('IEEE14_BUS_POSITIONS', () => {
  it('has exactly 14 entries', () => {
    expect(Object.keys(IEEE14_BUS_POSITIONS)).toHaveLength(14)
  })

  it('all positions are within [-80, 80] in both axes', () => {
    for (const [id, pos] of Object.entries(IEEE14_BUS_POSITIONS)) {
      expect(Math.abs(pos.x), `${id}.x out of range`).toBeLessThanOrEqual(80)
      expect(Math.abs(pos.z), `${id}.z out of range`).toBeLessThanOrEqual(80)
    }
  })

  it('no two buses share the same position within 5 world units', () => {
    const positions = Object.values(IEEE14_BUS_POSITIONS)
    for (let i = 0; i < positions.length; i++) {
      for (let j = i + 1; j < positions.length; j++) {
        const dx = positions[i].x - positions[j].x
        const dz = positions[i].z - positions[j].z
        const dist = Math.sqrt(dx * dx + dz * dz)
        expect(dist, `buses ${i} and ${j} too close`).toBeGreaterThan(5)
      }
    }
  })
})

// ── gridLayout ────────────────────────────────────────────────────────────────

function makeBuses(count: number): BusDto[] {
  return Array.from({ length: count }, (_, i) => ({
    id: `b${i}`,
    name: `Bus ${i}`,
    voltageKv: 220,
    voltagePu: 1.0,
    angleRad: 0,
    substationId: 's1',
  }))
}

describe('gridLayout', () => {
  it('returns an empty map for zero buses', () => {
    expect(gridLayout([]).size).toBe(0)
  })

  it('returns N positions for N buses', () => {
    expect(gridLayout(makeBuses(9)).size).toBe(9)
  })

  it('all positions are distinct', () => {
    const positions = [...gridLayout(makeBuses(16)).values()]
    const keys = positions.map((p) => `${p.x},${p.z}`)
    expect(new Set(keys).size).toBe(16)
  })

  it('adjacent buses are at least 15 world units apart', () => {
    const positions = [...gridLayout(makeBuses(4)).values()]
    for (let i = 0; i < positions.length; i++) {
      for (let j = i + 1; j < positions.length; j++) {
        const dx = positions[i].x - positions[j].x
        const dz = positions[i].z - positions[j].z
        const dist = Math.sqrt(dx * dx + dz * dz)
        expect(dist, `positions ${i} and ${j} too close`).toBeGreaterThan(15)
      }
    }
  })
})

// ── layoutBuses (selector) ────────────────────────────────────────────────────

describe('layoutBuses', () => {
  it('uses hardcoded position for known IEEE 14-bus ID', () => {
    const knownId = Object.keys(IEEE14_BUS_POSITIONS)[0]
    const buses = [{ id: knownId, name: 'Bus1', voltageKv: 220, voltagePu: 1, angleRad: 0, substationId: 's1' }]
    const positions = layoutBuses(buses)
    expect(positions.get(knownId)).toEqual(IEEE14_BUS_POSITIONS[knownId])
  })

  it('falls back to grid layout for unknown bus IDs', () => {
    const buses = makeBuses(4)
    const positions = layoutBuses(buses)
    expect(positions.size).toBe(4)
  })

  it('mixes hardcoded and grid positions when some IDs are known', () => {
    const knownId = Object.keys(IEEE14_BUS_POSITIONS)[0]
    const buses: BusDto[] = [
      { id: knownId, name: 'KnownBus', voltageKv: 220, voltagePu: 1, angleRad: 0, substationId: 's1' },
      { id: 'unknown-bus', name: 'UnknownBus', voltageKv: 110, voltagePu: 1, angleRad: 0, substationId: 's2' },
    ]
    const positions = layoutBuses(buses)
    expect(positions.size).toBe(2)
    expect(positions.get(knownId)).toEqual(IEEE14_BUS_POSITIONS[knownId])
    expect(positions.has('unknown-bus')).toBe(true)
  })
})
