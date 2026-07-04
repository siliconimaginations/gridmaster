import { describe, it, expect, vi, beforeEach } from 'vitest'

vi.mock('pixi.js')

import { NodeLayer } from '../NodeLayer'
import type { BusTextures } from '../NodeLayer'
import type { GridGraph, BusNode } from '../../../model/GridGraph'
import { Texture } from 'pixi.js'

// ── Helpers ────────────────────────────────────────────────────────────────────

function makeBus(id: string, role: 'gen' | 'sub' | 'load' = 'sub', overrides: Partial<BusNode> = {}): BusNode {
  return {
    id, role, name: id,
    voltageKv: 110, v: 1.0,
    genMw: 50, genMaxMw: 100, loadMw: 0,
    hasVoltageViolation: false,
    x: 100, y: 200,
    ...overrides,
  }
}

function makeTextures(): BusTextures {
  return { gen: new Texture(), sub: new Texture(), load: new Texture() }
}

function makeGraph(buses: BusNode[]): GridGraph {
  return {
    buses: new Map(buses.map(b => [b.id, b])),
    edges: [],
    adjacency: new Map(buses.map(b => [b.id, new Set()])),
  }
}

// ── NodeLayer ─────────────────────────────────────────────────────────────────

describe('NodeLayer', () => {
  let layer: NodeLayer

  beforeEach(() => {
    layer = new NodeLayer()
  })

  it('exposes a sortable container at zIndex 20', () => {
    expect(layer.container.zIndex).toBe(20)
    expect(layer.container.sortableChildren).toBe(true)
  })

  it('rebuild adds one child group per bus', () => {
    const graph = makeGraph([makeBus('A', 'gen'), makeBus('B', 'load'), makeBus('C')])
    layer.rebuild(graph, makeTextures(), 2)
    expect(layer.container.children.length).toBe(3)
  })

  it('rebuild clears previous children', () => {
    const graph1 = makeGraph([makeBus('A')])
    layer.rebuild(graph1, makeTextures(), 2)
    expect(layer.container.children.length).toBe(1)

    const graph2 = makeGraph([makeBus('A'), makeBus('B')])
    layer.rebuild(graph2, makeTextures(), 2)
    expect(layer.container.children.length).toBe(2)
  })

  it('each bus group has a sprite child labelled "sprite"', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    const sprite = group.getChildByName('sprite')
    expect(sprite).not.toBeNull()
  })

  it('each bus group has a voltage dot labelled "vdot"', () => {
    const graph = makeGraph([makeBus('A')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    expect(group.getChildByName('vdot')).not.toBeNull()
  })

  it('generator bus group has a state bar labelled "sbar"', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    expect(group.getChildByName('sbar')).not.toBeNull()
  })

  it('non-generator bus group has no state bar', () => {
    const graph = makeGraph([makeBus('B', 'load')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    expect(group.getChildByName('sbar')).toBeNull()
  })

  it('state bar hidden at lod < 2', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 1)
    const group = layer.container.children[0]
    const sbar = group.getChildByName('sbar')!
    expect(sbar.visible).toBe(false)
  })

  it('state bar visible at lod 2', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    const sbar = group.getChildByName('sbar')!
    expect(sbar.visible).toBe(true)
  })

  it('applyLod hides state bars at tier < 2', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 2)
    layer.applyLod(0)
    const group = layer.container.children[0]
    expect(group.getChildByName('sbar')!.visible).toBe(false)
  })

  it('applyLod shows state bars at tier 2', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 0) // start hidden
    layer.applyLod(2)
    const group = layer.container.children[0]
    expect(group.getChildByName('sbar')!.visible).toBe(true)
  })

  it('bus group has pointer events enabled', () => {
    const graph = makeGraph([makeBus('A')])
    layer.rebuild(graph, makeTextures(), 2)
    const group = layer.container.children[0]
    expect(group.eventMode).toBe('static')
    expect(group.cursor).toBe('pointer')
  })

  it('onBusClick fires when a bus pointertap event is triggered', () => {
    const graph = makeGraph([makeBus('A')])
    layer.rebuild(graph, makeTextures(), 2)
    const cb = vi.fn()
    layer.onBusClick(cb)
    // Find the 'pointertap' listener registered on the group
    const group = layer.container.children[0]
    // Simulate: call the handler registered via group.on('pointertap', ...)
    const calls = (group.on as ReturnType<typeof vi.fn>).mock.calls as [string, () => void][]
    const onCall = calls.find((call) => call[0] === 'pointertap')
    expect(onCall).toBeDefined()
    onCall![1]() // invoke handler
    expect(cb).toHaveBeenCalledWith(expect.objectContaining({ id: 'A' }))
  })

  it('refreshBus updates vdot without error', () => {
    const bus = makeBus('A', 'gen')
    const graph = makeGraph([bus])
    layer.rebuild(graph, makeTextures(), 2)
    bus.v = 1.08
    expect(() => layer.refreshBus(bus, 2)).not.toThrow()
  })

  it('refreshBus on unknown bus is a no-op', () => {
    layer.rebuild(makeGraph([makeBus('A')]), makeTextures(), 2)
    const ghost = makeBus('UNKNOWN')
    expect(() => layer.refreshBus(ghost, 2)).not.toThrow()
  })

  it('destroy does not throw', () => {
    expect(() => layer.destroy()).not.toThrow()
  })

  // ── LOD 0 icon system ────────────────────────────────────────────────────────

  it('each bus group has an icon child labelled "icon"', () => {
    const graph = makeGraph([makeBus('A', 'gen'), makeBus('B', 'sub'), makeBus('C', 'load')])
    layer.rebuild(graph, makeTextures(), 2)
    for (const group of layer.container.children) {
      expect(group.getChildByName('icon')).not.toBeNull()
    }
  })

  it('icon hidden and sprite visible when rebuilt at lod 1', () => {
    const graph = makeGraph([makeBus('A', 'gen')])
    layer.rebuild(graph, makeTextures(), 1)
    const group = layer.container.children[0]
    expect(group.getChildByName('icon')!.visible).toBe(false)
    expect(group.getChildByName('sprite')!.visible).toBe(true)
    expect(group.getChildByName('vdot')!.visible).toBe(true)
  })

  it('icon visible and sprite hidden when rebuilt at lod 0', () => {
    const graph = makeGraph([makeBus('A', 'sub')])
    layer.rebuild(graph, makeTextures(), 0)
    const group = layer.container.children[0]
    expect(group.getChildByName('icon')!.visible).toBe(true)
    expect(group.getChildByName('sprite')!.visible).toBe(false)
    expect(group.getChildByName('vdot')!.visible).toBe(false)
  })

  it('applyLod(0) shows icon and hides sprite/vdot', () => {
    const graph = makeGraph([makeBus('A', 'load')])
    layer.rebuild(graph, makeTextures(), 2)
    layer.applyLod(0)
    const group = layer.container.children[0]
    expect(group.getChildByName('icon')!.visible).toBe(true)
    expect(group.getChildByName('sprite')!.visible).toBe(false)
    expect(group.getChildByName('vdot')!.visible).toBe(false)
  })

  it('applyLod(2) hides icon and shows sprite/vdot', () => {
    const graph = makeGraph([makeBus('A', 'sub')])
    layer.rebuild(graph, makeTextures(), 0)
    layer.applyLod(2)
    const group = layer.container.children[0]
    expect(group.getChildByName('icon')!.visible).toBe(false)
    expect(group.getChildByName('sprite')!.visible).toBe(true)
    expect(group.getChildByName('vdot')!.visible).toBe(true)
  })

  it('refreshBus at lod 0 redraws icon without error', () => {
    const bus = makeBus('A', 'load', { loadMw: 200 })
    layer.rebuild(makeGraph([bus]), makeTextures(), 0)
    bus.hasVoltageViolation = true
    expect(() => layer.refreshBus(bus, 0)).not.toThrow()
  })

  it('load bus city-size: town icon drawn for low loadMw', () => {
    // Should not throw — just verifies correct code path executes
    const bus = makeBus('A', 'load', { loadMw: 50 })
    expect(() => layer.rebuild(makeGraph([bus]), makeTextures(), 0)).not.toThrow()
  })

  it('load bus city-size: city icon drawn for mid loadMw', () => {
    const bus = makeBus('A', 'load', { loadMw: 250 })
    expect(() => layer.rebuild(makeGraph([bus]), makeTextures(), 0)).not.toThrow()
  })

  it('load bus city-size: metro icon drawn for high loadMw', () => {
    const bus = makeBus('A', 'load', { loadMw: 800 })
    expect(() => layer.rebuild(makeGraph([bus]), makeTextures(), 0)).not.toThrow()
  })
})
