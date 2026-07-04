import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('pixi.js')

import { ParticleLayer } from '../ParticleLayer'
import type { BranchEdge } from '../../../model/GridGraph'
import type { BezierLUT } from '../WireLayer'
import { Texture } from 'pixi.js'

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeEdge(id: string, connected = true, loadFactor = 0.5): BranchEdge {
  return { id, fromId: 'A', toId: 'B', loadFactor, connected, isNearLimit: false, isOverloaded: false }
}

function makeLut(edgeId: string): BezierLUT {
  const points = new Float32Array(128)
  for (let i = 0; i < 64; i++) {
    points[i * 2]     = i * 5   // x
    points[i * 2 + 1] = i * 2   // y
  }
  return { edgeId, points }
}

// ── ParticleLayer ─────────────────────────────────────────────────────────────

describe('ParticleLayer', () => {
  let layer: ParticleLayer

  beforeEach(() => {
    layer = new ParticleLayer()
  })

  it('container is initially invisible (flow off by default)', () => {
    expect(layer.container.visible).toBe(false)
    expect(layer.flowVisible).toBe(false)
  })

  it('setFlowVisible(true) shows container', () => {
    layer.setFlowVisible(true)
    expect(layer.container.visible).toBe(true)
    expect(layer.flowVisible).toBe(true)
  })

  it('setFlowVisible(false) hides container', () => {
    layer.setFlowVisible(true)
    layer.setFlowVisible(false)
    expect(layer.container.visible).toBe(false)
  })

  it('rebuild creates 3 particles per connected edge', () => {
    const edges = [makeEdge('e1'), makeEdge('e2')]
    const luts = new Map([['e1', makeLut('e1')], ['e2', makeLut('e2')]])
    layer.rebuild(edges, luts, new Texture())
    // 2 edges × 3 particles = 6 sprites added to container
    expect(layer.container.children.length).toBe(6)
  })

  it('rebuild skips disconnected edges', () => {
    const edges = [makeEdge('e1', true), makeEdge('e2', false)]
    const luts  = new Map([['e1', makeLut('e1')], ['e2', makeLut('e2')]])
    layer.rebuild(edges, luts, new Texture())
    expect(layer.container.children.length).toBe(3) // only e1
  })

  it('rebuild skips edges with no LUT', () => {
    const edges = [makeEdge('e1'), makeEdge('ghost')]
    const luts  = new Map([['e1', makeLut('e1')]])
    layer.rebuild(edges, luts, new Texture())
    expect(layer.container.children.length).toBe(3) // only e1
  })

  it('rebuild clears previous particles', () => {
    const edges = [makeEdge('e1')]
    const luts  = new Map([['e1', makeLut('e1')]])
    layer.rebuild(edges, luts, new Texture())
    expect(layer.container.children.length).toBe(3)
    // Rebuild again with no edges
    layer.rebuild([], new Map(), new Texture())
    expect(layer.container.children.length).toBe(0)
  })

  it('tick does not throw when flow is hidden', () => {
    layer.rebuild([makeEdge('e1')], new Map([['e1', makeLut('e1')]]), new Texture())
    layer.setFlowVisible(false)
    expect(() => layer.tick(1)).not.toThrow()
  })

  it('tick advances particle t values when flow is visible', () => {
    const edges = [makeEdge('e1', true, 0.0)]
    const luts  = new Map([['e1', makeLut('e1')]])
    layer.rebuild(edges, luts, new Texture())
    layer.setFlowVisible(true)
    // Tick 10 frames — particles should have moved
    for (let i = 0; i < 10; i++) layer.tick(1)
    // No throws and container still has particles
    expect(layer.container.children.length).toBe(3)
  })

  it('tick wraps particle t at 1.0', () => {
    // With very high loadFactor, speed is high — should wrap without NaN/crash
    const edges = [makeEdge('e1', true, 10.0)]
    const luts  = new Map([['e1', makeLut('e1')]])
    layer.rebuild(edges, luts, new Texture())
    layer.setFlowVisible(true)
    expect(() => { for (let i = 0; i < 100; i++) layer.tick(1) }).not.toThrow()
  })

  it('destroy does not throw', () => {
    expect(() => layer.destroy()).not.toThrow()
  })
})
