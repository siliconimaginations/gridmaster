import { describe, it, expect } from 'vitest'
import { layoutGrid } from '../autoLayout'
import type { GridGraph, BusNode } from '../../../model/GridGraph'

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeBus(id: string, role: 'gen' | 'sub' | 'load' = 'sub', overrides: Partial<BusNode> = {}): BusNode {
  return {
    id, role, name: id,
    voltageKv: 110, v: 1.0,
    genMw: 0, genMaxMw: 0, loadMw: 0,
    hasVoltageViolation: false,
    x: 0, y: 0,
    ...overrides,
  }
}

function makeGraph(buses: BusNode[], connected = true): GridGraph {
  const busMap = new Map(buses.map(b => [b.id, b]))
  const adjacency = new Map(buses.map(b => [b.id, new Set<string>()]))
  const edges = []
  if (buses.length >= 2 && connected) {
    for (let i = 0; i < buses.length - 1; i++) {
      const a = buses[i].id, b = buses[i + 1].id
      adjacency.get(a)!.add(b)
      adjacency.get(b)!.add(a)
      edges.push({ id: `e${i}`, fromId: a, toId: b, loadFactor: 0.5, connected: true, isNearLimit: false, isOverloaded: false })
    }
  }
  return { buses: busMap, edges, adjacency }
}

const W = 1200, H = 800
const PADDING = 80

// ── layoutGrid ────────────────────────────────────────────────────────────────

describe('layoutGrid', () => {
  it('returns the same graph reference', () => {
    const graph = makeGraph([makeBus('A'), makeBus('B')])
    expect(layoutGrid(graph, W, H)).toBe(graph)
  })

  it('does nothing for empty graph', () => {
    const graph: GridGraph = { buses: new Map(), edges: [], adjacency: new Map() }
    expect(() => layoutGrid(graph, W, H)).not.toThrow()
  })

  it('assigns non-zero coordinates after layout', () => {
    const buses = Array.from({ length: 5 }, (_, i) => makeBus(`b${i}`, i === 0 ? 'gen' : i === 4 ? 'load' : 'sub'))
    const graph = makeGraph(buses)
    layoutGrid(graph, W, H)
    let nonZeroCount = 0
    for (const b of graph.buses.values()) {
      if (b.x !== 0 || b.y !== 0) nonZeroCount++
    }
    expect(nonZeroCount).toBeGreaterThan(0)
  })

  it('keeps all nodes within padded viewport after force-directed layout', () => {
    const buses = Array.from({ length: 8 }, (_, i) => makeBus(`b${i}`))
    const graph = makeGraph(buses)
    layoutGrid(graph, W, H)
    for (const b of graph.buses.values()) {
      expect(b.x).toBeGreaterThanOrEqual(PADDING - 1)
      expect(b.x).toBeLessThanOrEqual(W - PADDING + 1)
      expect(b.y).toBeGreaterThanOrEqual(PADDING - 1)
      expect(b.y).toBeLessThanOrEqual(H - PADDING + 1)
    }
  })

  it('uses geographic layout when ≥80% of buses have lat/lon', () => {
    const buses = [
      makeBus('a', 'gen',  { lat: 51.5, lon: -0.1  }),
      makeBus('b', 'sub',  { lat: 53.4, lon: -2.2  }),
      makeBus('c', 'load', { lat: 52.0, lon: -1.5  }),
      makeBus('d', 'sub',  { lat: 50.8, lon: -1.1  }),
      makeBus('e', 'gen',  { lat: 54.0, lon: -3.0  }),
    ]
    const graph = makeGraph(buses)
    layoutGrid(graph, W, H)

    // Geographic layout: nodes with more northerly lat should have lower y
    const aY = graph.buses.get('a')!.y
    const eY = graph.buses.get('e')!.y
    // 'e' is furthest north (lat 54) so should have lower y (higher on screen)
    expect(eY).toBeLessThan(aY)
  })

  it('falls back to force-directed when <80% have lat/lon', () => {
    // Only 1/5 has lat/lon — should still run without throwing
    const buses = [
      makeBus('a', 'gen', { lat: 51.5, lon: -0.1 }),
      makeBus('b'), makeBus('c'), makeBus('d'), makeBus('e'),
    ]
    const graph = makeGraph(buses)
    expect(() => layoutGrid(graph, W, H)).not.toThrow()
    // All coordinates should be set
    for (const b of graph.buses.values()) {
      expect(Number.isFinite(b.x)).toBe(true)
      expect(Number.isFinite(b.y)).toBe(true)
    }
  })

  it('nodes do not all collapse to the same point', () => {
    const buses = Array.from({ length: 6 }, (_, i) => makeBus(`b${i}`))
    const graph = makeGraph(buses)
    layoutGrid(graph, W, H)
    const positions = Array.from(graph.buses.values()).map(b => `${Math.round(b.x)},${Math.round(b.y)}`)
    // At least some positions must differ
    expect(new Set(positions).size).toBeGreaterThan(1)
  })
})
