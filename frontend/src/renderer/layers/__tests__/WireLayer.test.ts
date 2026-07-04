import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('pixi.js')

import { WireLayer } from '../WireLayer'
import type { GridGraph, BranchEdge, BusNode } from '../../../model/GridGraph'

// ── Fixtures ──────────────────────────────────────────────────────────────────

function makeBus(id: string, x: number, y: number, role: 'gen' | 'sub' | 'load' = 'sub'): BusNode {
  return {
    id, role, name: id,
    voltageKv: 110, v: 1.0,
    genMw: 0, genMaxMw: 0, loadMw: 0,
    hasVoltageViolation: false,
    x, y,
  }
}

function makeEdge(id: string, fromId: string, toId: string, overrides: Partial<BranchEdge> = {}): BranchEdge {
  return {
    id, fromId, toId,
    loadFactor: 0.5,
    connected: true,
    isNearLimit: false,
    isOverloaded: false,
    ...overrides,
  }
}

function makeGraph(overrides?: { edges?: BranchEdge[] }): GridGraph {
  const buses = new Map<string, BusNode>([
    ['A', makeBus('A', 100, 200, 'gen')],
    ['B', makeBus('B', 400, 300, 'load')],
    ['C', makeBus('C', 700, 150, 'sub')],
  ])
  const adjacency = new Map([['A', new Set(['B'])], ['B', new Set(['A', 'C'])], ['C', new Set(['B'])]])
  return {
    buses,
    adjacency,
    edges: overrides?.edges ?? [
      makeEdge('e1', 'A', 'B'),
      makeEdge('e2', 'B', 'C'),
    ],
  }
}

// ── WireLayer ─────────────────────────────────────────────────────────────────

describe('WireLayer', () => {
  let layer: WireLayer

  beforeEach(() => {
    layer = new WireLayer()
  })

  it('exposes a container', () => {
    expect(layer.container).toBeDefined()
  })

  it('container zIndex is 10', () => {
    expect(layer.container.zIndex).toBe(10)
  })

  it('luts is empty before update', () => {
    expect(layer.luts.size).toBe(0)
  })

  it('update populates luts for connected edges', () => {
    layer.update(makeGraph())
    expect(layer.luts.size).toBe(2)
    expect(layer.luts.has('e1')).toBe(true)
    expect(layer.luts.has('e2')).toBe(true)
  })

  it('update skips disconnected edges', () => {
    const graph = makeGraph({
      edges: [
        makeEdge('e1', 'A', 'B', { connected: true }),
        makeEdge('e2', 'B', 'C', { connected: false }),
      ],
    })
    layer.update(graph)
    expect(layer.luts.size).toBe(1)
    expect(layer.luts.has('e1')).toBe(true)
    expect(layer.luts.has('e2')).toBe(false)
  })

  it('update skips edges with missing bus nodes', () => {
    const graph = makeGraph({
      edges: [makeEdge('ghost', 'A', 'MISSING')],
    })
    layer.update(graph)
    expect(layer.luts.size).toBe(0)
  })

  it('each LUT has 64 samples (128 floats)', () => {
    layer.update(makeGraph())
    const lut = layer.luts.get('e1')!
    expect(lut.points.length).toBe(128)
    expect(lut.edgeId).toBe('e1')
  })

  it('LUT first point matches the from-bus x coordinate approximately', () => {
    layer.update(makeGraph())
    const lut = layer.luts.get('e1')!
    // First sample of bezier is at t=0, which equals the from-bus anchor
    expect(lut.points[0]).toBeCloseTo(100, 0) // bus A x=100
  })

  it('LUT last point matches the to-bus x coordinate approximately', () => {
    layer.update(makeGraph())
    const lut = layer.luts.get('e1')!
    // Last sample at t=1 equals the to-bus anchor
    expect(lut.points[126]).toBeCloseTo(400, 0) // bus B x=400
  })

  it('calling update twice clears old luts', () => {
    layer.update(makeGraph())
    expect(layer.luts.size).toBe(2)

    // Update with only 1 edge
    layer.update(makeGraph({ edges: [makeEdge('e1', 'A', 'B')] }))
    expect(layer.luts.size).toBe(1)
  })

  it('destroy does not throw', () => {
    expect(() => layer.destroy()).not.toThrow()
  })
})
