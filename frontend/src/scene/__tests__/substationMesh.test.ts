import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for substationMesh.ts.
 * Babylon.js is mocked — no WebGL required.
 */

vi.mock('@babylonjs/core', () => {
  class Color3 {
    constructor(public r = 0, public g = 0, public b = 0) {}
    static Black() { return new Color3() }
  }
  class Vector3 {
    constructor(public x = 0, public y = 0, public z = 0) {}
    clone() { return new Vector3(this.x, this.y, this.z) }
  }
  class StandardMaterial {
    diffuseColor: Color3 | undefined
    dispose = vi.fn()
    constructor(public name: string, _s: unknown) {}
  }
  const mesh = () => ({
    position: new Vector3(), material: null as unknown, dispose: vi.fn(),
    rotation: new Vector3(), receiveShadows: false, isVisible: true,
  })
  const MeshBuilder = {
    CreateBox:   vi.fn(mesh),
    CreateTorus: vi.fn(mesh),
  }
  return { Color3, Vector3, StandardMaterial, MeshBuilder }
})

vi.mock('../materials/ToonMaterial', () => ({
  createToonMaterial: vi.fn((_scene, colour) => ({ diffuseColor: colour })),
}))

import { Vector3 } from '@babylonjs/core'
import { substationStatus, createSubstationMesh, updateSubstationStatus } from '../meshes/substationMesh'
import type { ViolationDto } from '../../api/types'

const mockScene = {} as never

const makeViolation = (value: number, limit: number): ViolationDto => ({
  elementId: 'b1', elementType: 'BUS', violationType: 'VOLTAGE_HIGH', value, limit,
})

// ── substationStatus ──────────────────────────────────────────────────────────

describe('substationStatus', () => {
  it('returns ok for no violations', () => {
    expect(substationStatus([])).toBe('ok')
  })
  it('returns warning for minor violation (value <= limit * 1.1)', () => {
    expect(substationStatus([makeViolation(100, 100)])).toBe('warning')
  })
  it('returns fault for severe violation (value > limit * 1.1)', () => {
    expect(substationStatus([makeViolation(120, 100)])).toBe('fault')
  })
  it('fault takes priority over warning when both present', () => {
    expect(substationStatus([makeViolation(100, 100), makeViolation(120, 100)])).toBe('fault')
  })
})

// ── createSubstationMesh ──────────────────────────────────────────────────────

describe('createSubstationMesh', () => {
  it('returns building and ring', () => {
    const { building, ring } = createSubstationMesh(mockScene, new Vector3(0, 0, 0), 'ss1')
    expect(building).toBeDefined()
    expect(ring).toBeDefined()
  })

  it('building.position.y is 1 (elevated above ground plane)', () => {
    const { building } = createSubstationMesh(mockScene, new Vector3(0, 0, 0), 'ss1')
    expect(building.position.y).toBe(1)
  })

  it('ring material is green-ish for ok status (no violations)', () => {
    const { ring } = createSubstationMesh(mockScene, new Vector3(0, 0, 0), 'ss1')
    // ok colour = Color3(0.29, 0.86, 0.5) — green dominant
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((ring.material as any).diffuseColor.g).toBeGreaterThan(0.7)
  })

  it('ring material is amber for warning status', () => {
    const { ring } = createSubstationMesh(mockScene, new Vector3(), 'ss1', [makeViolation(100, 100)])
    // warning colour = Color3(0.98, 0.75, 0.14) — high red, low blue
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const c = (ring.material as any).diffuseColor
    expect(c.r).toBeGreaterThan(0.9)
    expect(c.b).toBeLessThan(0.3)
  })

  it('ring material is red-ish for fault status', () => {
    const { ring } = createSubstationMesh(mockScene, new Vector3(), 'ss1', [makeViolation(120, 100)])
    // fault colour = Color3(0.97, 0.53, 0.44) — high red, low blue
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const c = (ring.material as any).diffuseColor
    expect(c.r).toBeGreaterThan(0.9)
    expect(c.b).toBeLessThan(0.5)
  })
})

// ── updateSubstationStatus ────────────────────────────────────────────────────

describe('updateSubstationStatus', () => {
  let ring: ReturnType<typeof createSubstationMesh>['ring']

  beforeEach(() => {
    ring = createSubstationMesh(mockScene, new Vector3(), 'ss2').ring
  })

  it('updates diffuseColor to warning amber', () => {
    updateSubstationStatus(ring, 'warning')
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const c = (ring.material as any).diffuseColor
    expect(c.r).toBeGreaterThan(0.9)
    expect(c.b).toBeLessThan(0.3)
  })

  it('updates diffuseColor to fault red', () => {
    updateSubstationStatus(ring, 'fault')
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const c = (ring.material as any).diffuseColor
    expect(c.r).toBeGreaterThan(0.9)
    expect(c.b).toBeLessThan(0.5)
  })

  it('updates diffuseColor to ok green', () => {
    updateSubstationStatus(ring, 'ok')
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((ring.material as any).diffuseColor.g).toBeGreaterThan(0.7)
  })
})
