import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── Babylon.js mock ───────────────────────────────────────────────────────────
vi.mock('@babylonjs/core', () => {
  class Engine {
    runRenderLoop = vi.fn()
    resize = vi.fn()
    dispose = vi.fn()
    constructor(_canvas: unknown, _aa: boolean, _opts: unknown) {}
  }
  class Scene {
    render = vi.fn()
    dispose = vi.fn()
    constructor(_engine: unknown) {}
  }
  return { Engine, Scene }
})

// ── Sub-module mocks (no GPU needed) ─────────────────────────────────────────
vi.mock('../camera', () => ({ createIsometricCamera: vi.fn() }))
vi.mock('../ground', () => ({ createGround: vi.fn() }))
vi.mock('../lighting', () => ({ createSceneLighting: vi.fn() }))

const mockUpdateNetwork = vi.fn()
const mockDisposeAll = vi.fn()

vi.mock('../meshes/MeshRegistry', () => ({
  MeshRegistry: vi.fn().mockImplementation(() => ({
    updateNetwork: mockUpdateNetwork,
    disposeAll: mockDisposeAll,
  })),
}))

import { SceneManager } from '../SceneManager'
import type { GridNetworkDto, ViolationDto } from '../../api/types'

// ── helpers ───────────────────────────────────────────────────────────────────

const makeCanvas = () => ({ addEventListener: vi.fn(), removeEventListener: vi.fn() } as unknown as HTMLCanvasElement)

const makeNetwork = (): GridNetworkDto => ({
  buses: [], generators: [], loads: [], branches: [],
})

const makeViolation = (): ViolationDto => ({
  elementId: 'b1', type: 'VOLTAGE_HIGH', value: 1.15, limit: 1.05,
})

// ── tests ─────────────────────────────────────────────────────────────────────

describe('SceneManager', () => {
  let manager: SceneManager

  beforeEach(() => {
    vi.clearAllMocks()
    // Suppress addEventListener on window (not available in Node)
    vi.spyOn(window, 'addEventListener').mockImplementation(vi.fn())
    vi.spyOn(window, 'removeEventListener').mockImplementation(vi.fn())
    manager = new SceneManager(makeCanvas())
  })

  it('delegates updateNetwork to MeshRegistry with provided violations', () => {
    const network = makeNetwork()
    const violations = [makeViolation()]
    manager.updateNetwork(network, violations)
    expect(mockUpdateNetwork).toHaveBeenCalledWith(network, violations)
  })

  it('updateNetwork uses cached violations when none supplied', () => {
    const violations = [makeViolation()]
    manager.updateViolations(violations)      // cache violations
    vi.clearAllMocks()

    const network = makeNetwork()
    manager.updateNetwork(network)             // no violations arg
    expect(mockUpdateNetwork).toHaveBeenCalledWith(network, violations)
  })

  it('updateViolations re-calls MeshRegistry.updateNetwork with cached network', () => {
    const network = makeNetwork()
    manager.updateNetwork(network)            // cache network
    vi.clearAllMocks()

    const violations = [makeViolation()]
    manager.updateViolations(violations)
    expect(mockUpdateNetwork).toHaveBeenCalledWith(network, violations)
  })

  it('updateNetwork(null) passes null to MeshRegistry', () => {
    manager.updateNetwork(null)
    expect(mockUpdateNetwork).toHaveBeenCalledWith(null, [])
  })

  it('dispose calls meshRegistry.disposeAll before scene and engine disposal', () => {
    const callOrder: string[] = []
    mockDisposeAll.mockImplementation(() => callOrder.push('meshRegistry'))
    manager.dispose()
    expect(mockDisposeAll).toHaveBeenCalledOnce()
    expect(callOrder[0]).toBe('meshRegistry')
  })
})
