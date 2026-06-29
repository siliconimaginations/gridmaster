import { describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for ground.ts and lighting.ts.
 * Babylon.js is mocked — no WebGL required.
 */

vi.mock('@babylonjs/core', () => {
  class Color3 {
    constructor(public r = 0, public g = 0, public b = 0) {}
  }
  class Vector3 {
    constructor(public x = 0, public y = 0, public z = 0) {}
    clone() { return new Vector3(this.x, this.y, this.z) }
    normalize() { return this }
  }
  class DirectionalLight {
    diffuse: Color3 | undefined
    specular: Color3 | undefined
    intensity = 0
    constructor(public name: string, _dir: Vector3, _scene: unknown) {}
  }
  class HemisphericLight {
    diffuse: Color3 | undefined
    groundColor: Color3 | undefined
    specular: Color3 | undefined
    intensity = 0
    constructor(public name: string, _dir: Vector3, _scene: unknown) {}
  }
  const mesh = () => ({
    position: { y: 0 },
    rotation: { y: 0 },
    material: null as unknown,
    receiveShadows: false,
  })
  const MeshBuilder = {
    CreateGround: vi.fn(mesh),
  }
  const Scene = { FOGMODE_EXP2: 3 }
  return { Color3, Vector3, DirectionalLight, HemisphericLight, MeshBuilder, Scene }
})

vi.mock('../materials/ToonMaterial', () => ({
  createToonMaterial: vi.fn((_scene, colour) => ({ diffuseColor: colour })),
}))

import { createGround } from '../ground'
import { createSceneLighting } from '../lighting'

// ── createGround ──────────────────────────────────────────────────────────────

describe('createGround', () => {
  const mockScene = {} as never

  it('returns ground and river meshes', () => {
    const { ground, river } = createGround(mockScene)
    expect(ground).toBeDefined()
    expect(river).toBeDefined()
  })

  it('ground.receiveShadows is true', () => {
    const { ground } = createGround(mockScene)
    expect(ground.receiveShadows).toBe(true)
  })

  it('ground.material is set', () => {
    const { ground } = createGround(mockScene)
    expect(ground.material).not.toBeNull()
  })

  it('river is elevated slightly above ground (position.y > 0)', () => {
    const { river } = createGround(mockScene)
    expect(river.position.y).toBeGreaterThan(0)
  })

  it('river is rotated (rotation.y > 0)', () => {
    const { river } = createGround(mockScene)
    expect(river.rotation.y).toBeGreaterThan(0)
  })
})

// ── createSceneLighting ───────────────────────────────────────────────────────

describe('createSceneLighting', () => {
  it('returns sun and ambient lights', () => {
    const mockScene: Record<string, unknown> = {}
    const { sun, ambient } = createSceneLighting(mockScene as never)
    expect(sun).toBeDefined()
    expect(ambient).toBeDefined()
  })

  it('sun intensity > 1 (primary light source)', () => {
    const mockScene: Record<string, unknown> = {}
    const { sun } = createSceneLighting(mockScene as never)
    expect(sun.intensity).toBeGreaterThan(1)
  })

  it('ambient intensity < 1 (fill light)', () => {
    const mockScene: Record<string, unknown> = {}
    const { ambient } = createSceneLighting(mockScene as never)
    expect(ambient.intensity).toBeLessThan(1)
  })

  it('sets scene fog mode and density', () => {
    const mockScene: Record<string, unknown> = {}
    createSceneLighting(mockScene as never)
    expect(mockScene.fogMode).toBe(3)  // Scene.FOGMODE_EXP2
    expect(mockScene.fogDensity).toBeCloseTo(0.006, 6)
  })

  it('sets scene fog color', () => {
    const mockScene: Record<string, unknown> = {}
    createSceneLighting(mockScene as never)
    expect(mockScene.fogColor).toBeDefined()
  })
})
