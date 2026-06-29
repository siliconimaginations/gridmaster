import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * Unit tests for mesh factory functions.
 * Babylon.js is mocked — no WebGL required.
 */

vi.mock('@babylonjs/core', () => {
  class Color3 {
    constructor(public r = 0, public g = 0, public b = 0) {}
    static Black() { return new Color3() }
  }
  class Color4 {
    constructor(public r = 0, public g = 0, public b = 0, public a = 1) {}
  }
  class Vector3 {
    constructor(public x = 0, public y = 0, public z = 0) {}
    clone() { return new Vector3(this.x, this.y, this.z) }
    static Distance(a: Vector3, b: Vector3) {
      return Math.sqrt((a.x-b.x)**2 + (a.z-b.z)**2)
    }
    static Lerp(a: Vector3, b: Vector3, t: number) {
      return new Vector3(a.x+(b.x-a.x)*t, a.y+(b.y-a.y)*t, a.z+(b.z-a.z)*t)
    }
    subtract(o: Vector3) { return new Vector3(this.x-o.x, this.y-o.y, this.z-o.z) }
    normalize() { return this }
    negate() { return new Vector3(-this.x, -this.y, -this.z) }
  }
  class StandardMaterial {
    diffuseColor: Color3 | undefined
    specularColor: Color3 | undefined
    ambientColor: Color3 | undefined
    emissiveColor: Color3 | undefined
    emissiveTexture: unknown = null
    backFaceCulling = true
    disableLighting = false
    dispose = vi.fn()
    constructor(public name: string, _s: unknown) {}
  }
  const mesh = () => ({
    position: new Vector3(), material: null as unknown, dispose: vi.fn(),
    rotation: new Vector3(), receiveShadows: false, isVisible: true,
  })
  const MeshBuilder = {
    CreateCylinder: vi.fn(mesh),
    CreateTorus:    vi.fn(mesh),
    CreateBox:      vi.fn(mesh),
    CreateTube:     vi.fn(mesh),
    CreateGround:   vi.fn(mesh),
  }
  class ParticleSystem {
    particleTexture: unknown = null
    emitter: unknown = null
    minSize = 0; maxSize = 0; emitRate = 0
    minLifeTime = 0; maxLifeTime = 0
    minEmitPower = 0; maxEmitPower = 0
    direction1: unknown = null; direction2: unknown = null
    color1: unknown = null; color2: unknown = null; colorDead: unknown = null
    constructor(public name: string, _cap: number, _s: unknown) {}
    start = vi.fn()
    dispose = vi.fn()
  }
  class DynamicTexture {
    wrapU = 0; wrapV = 0; uOffset = 0; hasAlpha = false
    constructor(_n: string, _s: unknown, _scene: unknown) {}
    getContext() {
      return {
        clearRect: vi.fn(), fillRect: vi.fn(),
        fillStyle: '', beginPath: vi.fn(), arc: vi.fn(), fill: vi.fn(),
      }
    }
    update = vi.fn()
  }
  return { Color3, Color4, Vector3, StandardMaterial, MeshBuilder, ParticleSystem, DynamicTexture }
})

import { Color3, Vector3 } from '@babylonjs/core'
import type { BranchDto, GeneratorDto, LoadDto, ViolationDto } from '../../api/types'
import { cityTier, createCityMesh } from '../meshes/cityMesh'
import { createGeneratorMesh, generatorStatus, towerColour } from '../meshes/generatorMesh'
import { createLineMesh, lineColour } from '../meshes/lineMesh'
import { createFlowParticles, resetDotTexture } from '../meshes/particleFlow'

const mockScene = {
  onBeforeRenderObservable: { add: vi.fn(), removeCallback: vi.fn() },
} as never

// ── generatorMesh ─────────────────────────────────────────────────────────────

const makeGen = (committed: boolean, activePowerMw: number): GeneratorDto => ({
  id: 'g1', busId: 'b1', name: 'Gen1', maxActivePowerMw: 200, fuelType: 'GAS',
  committed, activePowerMw,
})

const makeViolation = (elementId: string): ViolationDto => ({
  elementId, elementType: 'LINE', violationType: 'OVERLOAD', value: 120, limit: 100,
})

describe('generatorStatus', () => {
  it('committed + active → online', () => {
    expect(generatorStatus(makeGen(true, 100), [])).toBe('online')
  })
  it('committed + zero MW → warning', () => {
    expect(generatorStatus(makeGen(true, 0), [])).toBe('warning')
  })
  it('not committed → offline', () => {
    expect(generatorStatus(makeGen(false, 0), [])).toBe('offline')
  })
  it('violation for this generator → fault', () => {
    expect(generatorStatus(makeGen(true, 100), [makeViolation('g1')])).toBe('fault')
  })
})

describe('towerColour', () => {
  it('offline → dark charcoal regardless of fuel type', () => {
    const c = towerColour('GAS', 'offline') as Color3
    // Dark charcoal: all channels < 0.25
    expect(c.r).toBeLessThan(0.25)
    expect(c.g).toBeLessThan(0.25)
    expect(c.b).toBeLessThan(0.25)
  })
  it('fault → red override regardless of fuel type', () => {
    const c = towerColour('WIND', 'fault') as Color3
    // Red: r > 0.8, b < 0.4
    expect(c.r).toBeGreaterThan(0.8)
    expect(c.b).toBeLessThan(0.4)
  })
  it('online GAS → steel blue (b dominant)', () => {
    const c = towerColour('GAS', 'online') as Color3
    expect(c.b).toBeGreaterThan(0.7)
  })
  it('online SOLAR → golden yellow (r+g high, b low)', () => {
    const c = towerColour('SOLAR', 'online') as Color3
    expect(c.r).toBeGreaterThan(0.8)
    expect(c.b).toBeLessThan(0.3)
  })
  it('unknown fuel → falls back to default warm grey', () => {
    const c = towerColour('UNKNOWN_FUEL', 'online') as Color3
    // warm grey ≈ (0.61, 0.64, 0.686) — all channels similar, mid-range
    expect(c.r).toBeGreaterThan(0.5)
    expect(Math.abs(c.r - c.g)).toBeLessThan(0.1)
  })
})

describe('createGeneratorMesh', () => {
  it('returns tower and ring meshes', () => {
    const { tower, ring } = createGeneratorMesh(mockScene, new Vector3(0,0,0), makeGen(true, 100))
    expect(tower).toBeDefined()
    expect(ring).toBeDefined()
  })
  it('ring material colour is green for online generator', () => {
    const { ring } = createGeneratorMesh(mockScene, new Vector3(0,0,0), makeGen(true, 100))
    const mat = ring.material as InstanceType<typeof import('@babylonjs/core').StandardMaterial>
    // green is approximately (0.29, 0.86, 0.5)
    expect((mat.diffuseColor as Color3).g).toBeGreaterThan(0.7)
  })
  it('ring colour is grey for offline generator', () => {
    const { ring } = createGeneratorMesh(mockScene, new Vector3(0,0,0), makeGen(false, 0))
    const mat = ring.material as InstanceType<typeof import('@babylonjs/core').StandardMaterial>
    // grey: all channels similar and low saturation
    const c = mat.diffuseColor as Color3
    expect(Math.abs(c.r - c.g)).toBeLessThan(0.1)
  })
  it('tower body is dark charcoal for offline generator', () => {
    const { tower } = createGeneratorMesh(mockScene, new Vector3(0,0,0), makeGen(false, 0))
    const mat = tower.material as InstanceType<typeof import('@babylonjs/core').StandardMaterial>
    const c = mat.diffuseColor as Color3
    expect(c.r).toBeLessThan(0.25)
    expect(c.g).toBeLessThan(0.25)
  })
  it('tower body reflects GAS fuel type for online generator', () => {
    const gasGen: GeneratorDto = { ...makeGen(true, 100), fuelType: 'GAS' }
    const { tower } = createGeneratorMesh(mockScene, new Vector3(0,0,0), gasGen)
    const mat = tower.material as InstanceType<typeof import('@babylonjs/core').StandardMaterial>
    // GAS = (0.4, 0.6, 0.85) — blue channel dominant
    expect((mat.diffuseColor as Color3).b).toBeGreaterThan(0.7)
  })
  it('chimney is non-null for thermal fuel types (#270)', () => {
    for (const fuel of ['COAL', 'GAS', 'CCGT', 'NUCLEAR']) {
      const gen: GeneratorDto = { ...makeGen(true, 100), fuelType: fuel }
      const { chimney } = createGeneratorMesh(mockScene, new Vector3(0,0,0), gen)
      expect(chimney, `expected chimney for ${fuel}`).not.toBeNull()
    }
  })
  it('chimney is null for renewable fuel types (#270)', () => {
    for (const fuel of ['WIND', 'SOLAR', 'HYDRO']) {
      const gen: GeneratorDto = { ...makeGen(true, 100), fuelType: fuel }
      const { chimney } = createGeneratorMesh(mockScene, new Vector3(0,0,0), gen)
      expect(chimney, `expected no chimney for ${fuel}`).toBeNull()
    }
  })
})

// ── cityMesh ──────────────────────────────────────────────────────────────────

describe('cityTier', () => {
  it('< 100 MW → village', () => expect(cityTier(50)).toBe('village'))
  it('100–500 MW → town',   () => expect(cityTier(300)).toBe('town'))
  it('> 500 MW → city',     () => expect(cityTier(600)).toBe('city'))
})

describe('createCityMesh', () => {
  const makeLoad = (mw: number): LoadDto =>
    ({ id: 'l1', busId: 'b1', name: 'Load1', activePowerMw: mw, reactivePowerMvar: 0 })

  it('village tier creates 3 buildings', () => {
    expect(createCityMesh(mockScene, new Vector3(), makeLoad(50))).toHaveLength(3)
  })
  it('town tier creates 5 buildings', () => {
    expect(createCityMesh(mockScene, new Vector3(), makeLoad(200))).toHaveLength(5)
  })
  it('city tier creates 6 buildings', () => {
    expect(createCityMesh(mockScene, new Vector3(), makeLoad(600))).toHaveLength(6)
  })
})

// ── lineMesh ──────────────────────────────────────────────────────────────────

const makeBranch = (connected: boolean, loadingPercent: number): BranchDto => ({
  id: 'br1', fromBusId: 'b1', toBusId: 'b2',
  activePowerMw: 100, reactivePowerMvar: 10, connected, loadingPercent,
})

describe('createLineMesh', () => {
  it('returns tube, hitMesh, and pylons', () => {
    const result = createLineMesh(mockScene, new Vector3(0,4,0), new Vector3(10,4,0), makeBranch(true, 50))
    expect(result.tube).toBeDefined()
    expect(result.hitMesh).toBeDefined()
    expect(result.pylons).toBeDefined()
  })
  it('tube.isPickable is false — selection handled by hitMesh', () => {
    const { tube } = createLineMesh(mockScene, new Vector3(0,4,0), new Vector3(10,4,0), makeBranch(true, 50))
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((tube as any).isPickable).toBe(false)
  })
  it('hitMesh.isPickable is true', () => {
    const { hitMesh } = createLineMesh(mockScene, new Vector3(0,4,0), new Vector3(10,4,0), makeBranch(true, 50))
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((hitMesh as any).isPickable).toBe(true)
  })
})

describe('lineColour', () => {
  it('< 70% loading → white (high green)', () => {
    const c = lineColour(makeBranch(true, 50)) as Color3
    expect(c.r).toBeGreaterThan(0.9)
  })
  it('70–90% loading → amber (high red+green)', () => {
    const c = lineColour(makeBranch(true, 80)) as Color3
    expect(c.r).toBeGreaterThan(0.8)
    expect(c.b).toBeLessThan(0.3)
  })
  it('> 90% loading → red (high red, low blue)', () => {
    const c = lineColour(makeBranch(true, 95)) as Color3
    expect(c.r).toBeGreaterThan(0.8)
    expect(c.b).toBeLessThan(0.5)
  })
  it('disconnected → grey', () => {
    const c = lineColour(makeBranch(false, 0)) as Color3
    expect(Math.abs(c.r - c.g)).toBeLessThan(0.1)
  })
})

// ── particleFlow (FlowDash) ───────────────────────────────────────────────────

describe('createFlowParticles', () => {
  beforeEach(() => resetDotTexture())

  it('returns null for disconnected branch', () => {
    const dash = createFlowParticles(mockScene, new Vector3(), new Vector3(10,0,0), makeBranch(false, 50))
    expect(dash).toBeNull()
  })
  it('returns null for zero-flow branch', () => {
    const branch = { ...makeBranch(true, 0), activePowerMw: 0 }
    expect(createFlowParticles(mockScene, new Vector3(), new Vector3(10,0,0), branch)).toBeNull()
  })
  it('returns a FlowDash for an active branch', () => {
    const dash = createFlowParticles(mockScene, new Vector3(), new Vector3(10,0,0), makeBranch(true, 50))
    expect(dash).not.toBeNull()
  })
  it('FlowDash.start() registers a scene observer', () => {
    const dash = createFlowParticles(mockScene, new Vector3(), new Vector3(10,0,0), makeBranch(true, 50))
    dash!.start()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((mockScene as any).onBeforeRenderObservable.add).toHaveBeenCalled()
  })
  it('FlowDash.dispose() removes the scene observer', () => {
    const dash = createFlowParticles(mockScene, new Vector3(), new Vector3(10,0,0), makeBranch(true, 50))
    dash!.start()
    dash!.dispose()
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((mockScene as any).onBeforeRenderObservable.removeCallback).toHaveBeenCalled()
  })
})
