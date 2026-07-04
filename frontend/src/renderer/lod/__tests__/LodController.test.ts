import { describe, it, expect, vi } from 'vitest'
import { LodController } from '../LodController'

describe('LodController', () => {
  it('starts at tier 1 (mid-zoom)', () => {
    const lod = new LodController()
    expect(lod.tier).toBe(1)
  })

  it('tier 0 for scale < 0.35', () => {
    const lod = new LodController()
    lod.update(0.20)
    expect(lod.tier).toBe(0)
  })

  it('tier 1 for scale in [0.35, 0.70)', () => {
    const lod = new LodController()
    lod.update(0.20) // move away from 1 first
    lod.update(0.50)
    expect(lod.tier).toBe(1)
  })

  it('tier 2 for scale >= 0.70', () => {
    const lod = new LodController()
    lod.update(1.0)
    expect(lod.tier).toBe(2)
  })

  it('boundary: scale exactly 0.35 is tier 1', () => {
    const lod = new LodController()
    lod.update(0.10) // force to tier 0
    lod.update(0.35)
    expect(lod.tier).toBe(1)
  })

  it('boundary: scale exactly 0.70 is tier 2', () => {
    const lod = new LodController()
    lod.update(0.70)
    expect(lod.tier).toBe(2)
  })

  it('fires onChange callback when tier changes', () => {
    const lod = new LodController()
    const cb = vi.fn()
    lod.onChange(cb)
    lod.update(1.0) // 1→2
    expect(cb).toHaveBeenCalledOnce()
    expect(cb).toHaveBeenCalledWith(2, 1)
  })

  it('does not fire onChange when tier stays the same', () => {
    const lod = new LodController()
    const cb = vi.fn()
    lod.onChange(cb)
    lod.update(0.40) // still tier 1
    lod.update(0.60) // still tier 1
    expect(cb).not.toHaveBeenCalled()
  })

  it('fires onChange with correct prev and next tiers', () => {
    const lod = new LodController()
    const calls: [number, number][] = []
    lod.onChange((next, prev) => calls.push([next, prev]))

    lod.update(0.20) // 1 → 0
    lod.update(1.50) // 0 → 2
    lod.update(0.50) // 2 → 1

    expect(calls).toEqual([[0, 1], [2, 0], [1, 2]])
  })

  it('unsubscribe removes the callback', () => {
    const lod = new LodController()
    const cb = vi.fn()
    const unsub = lod.onChange(cb)
    unsub()
    lod.update(1.0)
    expect(cb).not.toHaveBeenCalled()
  })

  it('supports multiple callbacks simultaneously', () => {
    const lod = new LodController()
    const cb1 = vi.fn()
    const cb2 = vi.fn()
    lod.onChange(cb1)
    lod.onChange(cb2)
    lod.update(1.0)
    expect(cb1).toHaveBeenCalledOnce()
    expect(cb2).toHaveBeenCalledOnce()
  })

  it('very high scale returns tier 2', () => {
    const lod = new LodController()
    lod.update(10)
    expect(lod.tier).toBe(2)
  })
})
