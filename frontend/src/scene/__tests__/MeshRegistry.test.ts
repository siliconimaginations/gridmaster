import { beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('@babylonjs/core', () => {
  class Color3 { constructor(public r=0,public g=0,public b=0){} static Black(){return new Color3()} }
  class Color4 { constructor(public r=0,public g=0,public b=0,public a=1){} }
  class Vector3 {
    constructor(public x=0,public y=0,public z=0){}
    clone(){ return new Vector3(this.x,this.y,this.z) }
    static Distance(_a: unknown,_b: unknown){ return 50 }
    static Lerp(a: Vector3,b: Vector3,t: number){ return new Vector3(a.x+(b.x-a.x)*t,0,a.z+(b.z-a.z)*t) }
    subtract(o: Vector3){ return new Vector3(this.x-o.x,0,this.z-o.z) }
    normalize(){ return this }
    negate(){ return new Vector3(-this.x,0,-this.z) }
  }
  class StandardMaterial {
    diffuseColor: Color3 | undefined
    specularColor: Color3 | undefined
    ambientColor: Color3 | undefined
    emissiveColor: Color3 | undefined
    disableLighting = false
    constructor(public name: string,_s: unknown){}
  }
  const makeMesh = () => ({
    position: new Vector3(), material: null as unknown,
    dispose: vi.fn(), rotation: new Vector3(), receiveShadows: false,
  })
  const MeshBuilder = {
    CreateCylinder: vi.fn(makeMesh), CreateTorus: vi.fn(makeMesh), CreateBox: vi.fn(makeMesh),
    CreateTube: vi.fn(makeMesh), CreateGround: vi.fn(makeMesh),
  }
  class ParticleSystem {
    particleTexture=null; emitter=null; minSize=0; maxSize=0; emitRate=0
    minLifeTime=0; maxLifeTime=0; minEmitPower=0; maxEmitPower=0
    direction1=null; direction2=null; color1=null; color2=null; colorDead=null
    constructor(public name: string,_cap: number,_s: unknown){}
    start=vi.fn(); dispose=vi.fn()
  }
  class DynamicTexture {
    constructor(_n: string,_s: unknown,_scene: unknown){}
    getContext(){ return { clearRect:vi.fn(),fillStyle:'',beginPath:vi.fn(),arc:vi.fn(),fill:vi.fn() } }
    update=vi.fn()
  }
  return { Color3, Color4, Vector3, StandardMaterial, MeshBuilder, ParticleSystem, DynamicTexture }
})

import { MeshBuilder } from '@babylonjs/core'
import type { BranchDto, BusDto, GeneratorDto, GridNetworkDto, LoadDto } from '../../api/types'
import { MeshRegistry } from '../meshes/MeshRegistry'

const mockScene = {} as never

const makeBus = (id: string): BusDto => ({ id, name: id, voltageKv: 220, voltagePu: 1, angleRad: 0, substationId: `s_${id}` })
const makeGen = (id: string): GeneratorDto => ({ id, busId: 'b1', name: id, activePowerMw: 100, maxActivePowerMw: 200, committed: true, fuelType: 'GAS' })
const makeLoad = (id: string): LoadDto => ({ id, busId: 'b2', name: id, activePowerMw: 50, reactivePowerMvar: 0 })
const makeBranch = (id: string): BranchDto => ({ id, fromBusId: 'b1', toBusId: 'b2', activePowerMw: 100, reactivePowerMvar: 10, connected: true, loadingPercent: 50 })

function makeNetwork(genIds: string[], loadIds: string[], branchIds: string[]): GridNetworkDto {
  return {
    buses: ['b1', 'b2'].map(makeBus),
    generators: genIds.map(makeGen),
    loads: loadIds.map(makeLoad),
    branches: branchIds.map(makeBranch),
  }
}

describe('MeshRegistry', () => {
  let registry: MeshRegistry

  beforeEach(() => {
    vi.clearAllMocks()
    registry = new MeshRegistry(mockScene)
  })

  it('creates meshes on first updateNetwork call', () => {
    registry.updateNetwork(makeNetwork(['g1'], [], []))
    expect(vi.mocked(MeshBuilder.CreateCylinder)).toHaveBeenCalled()
  })

  it('updateNetwork(null) disposes all meshes without error', () => {
    registry.updateNetwork(makeNetwork(['g1'], ['l1'], ['br1']))
    expect(() => registry.updateNetwork(null)).not.toThrow()
  })

  it('removes ghost generator when it disappears from next snapshot', () => {
    // First snapshot: g1 + g2
    registry.updateNetwork(makeNetwork(['g1', 'g2'], [], []))
    // All cylinders created so far (tower for each generator)
    const allCylinders = vi.mocked(MeshBuilder.CreateCylinder).mock.results.map((r) => r.value)
    expect(allCylinders.length).toBeGreaterThan(0)

    // Second snapshot: g1 only — g2 should be pruned
    registry.updateNetwork(makeNetwork(['g1'], [], []))
    // At least one cylinder's dispose should have been called (g2's tower)
    const disposed = allCylinders.filter((m) => m.dispose.mock.calls.length > 0)
    expect(disposed.length).toBeGreaterThan(0)
  })

  it('is idempotent — same network twice does not create duplicate meshes', () => {
    const network = makeNetwork(['g1'], ['l1'], ['br1'])
    registry.updateNetwork(network)
    const callsAfterFirst = vi.mocked(MeshBuilder.CreateCylinder).mock.calls.length
    registry.updateNetwork(network)
    // No new cylinder meshes should have been created
    expect(vi.mocked(MeshBuilder.CreateCylinder).mock.calls.length).toBe(callsAfterFirst)
  })

  it('updates line colour on subsequent calls without creating new tube', () => {
    const network = makeNetwork([], [], ['br1'])
    registry.updateNetwork(network)
    const tubeCalls = vi.mocked(MeshBuilder.CreateTube).mock.calls.length
    // Same network again — no new tube
    registry.updateNetwork(network)
    expect(vi.mocked(MeshBuilder.CreateTube).mock.calls.length).toBe(tubeCalls)
  })

  it('updates substation status ring colour on second call without creating new building', () => {
    const network = makeNetwork([], [], [])
    registry.updateNetwork(network)
    const boxCalls = vi.mocked(MeshBuilder.CreateBox).mock.calls.length
    // Second call — no new Box meshes (substation building) should be created
    registry.updateNetwork(network)
    expect(vi.mocked(MeshBuilder.CreateBox).mock.calls.length).toBe(boxCalls)
  })

})
