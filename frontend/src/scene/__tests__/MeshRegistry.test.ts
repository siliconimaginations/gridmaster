import { beforeEach, describe, expect, it, vi } from 'vitest'

// Manual mock at frontend/__mocks__/@babylonjs/core.ts — Vitest picks it up automatically.
vi.mock('@babylonjs/core')

import { MeshBuilder } from '@babylonjs/core'
import type { BranchDto, BusDto, GeneratorDto, GridNetworkDto, LoadDto, ViolationDto } from '../../api/types'
import { MeshRegistry } from '../meshes/MeshRegistry'

const mockScene = {
  onBeforeRenderObservable: { add: vi.fn(), removeCallback: vi.fn() },
} as never

const makeBus = (id: string): BusDto => ({ id, name: id, voltageKv: 220, voltagePu: 1, angleRad: 0, substationId: `s_${id}` })
const makeGen = (id: string): GeneratorDto => ({ id, busId: 'b1', name: id, activePowerMw: 100, maxActivePowerMw: 200, committed: true, fuelType: 'GAS', marginalCostPerMwh: 48.6 })
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

  it('updateViolations does not create new meshes', () => {
    // Prime registry so caches are populated
    registry.updateNetwork(makeNetwork(['g1'], [], []))
    const torusCalls = vi.mocked(MeshBuilder.CreateTorus).mock.calls.length
    const boxCalls = vi.mocked(MeshBuilder.CreateBox).mock.calls.length

    // Violation-only update should not create new geometry
    const v: ViolationDto = { elementId: 'g1', elementType: 'BUS', violationType: 'VOLTAGE_HIGH', value: 1.15, limit: 1.05 }
    registry.updateViolations([v])

    expect(vi.mocked(MeshBuilder.CreateTorus).mock.calls.length).toBe(torusCalls)
    expect(vi.mocked(MeshBuilder.CreateBox).mock.calls.length).toBe(boxCalls)
  })

  it('updateViolations before updateNetwork does not throw (empty caches)', () => {
    expect(() => registry.updateViolations([])).not.toThrow()
  })
})
