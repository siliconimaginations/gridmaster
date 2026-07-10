import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── Babylon.js mock ───────────────────────────────────────────────────────────
let capturedPointerHandler: ((pi: { type: number }) => void) | null = null

vi.mock('@babylonjs/core', () => {
  class Color3 { constructor(public r = 0, public g = 0, public b = 0) {} }
  class Color4 { constructor(public r = 0, public g = 0, public b = 0, public a = 1) {} }
  class Engine {
    runRenderLoop = vi.fn()
    resize = vi.fn()
    dispose = vi.fn()
    constructor(_canvas: unknown, _aa: boolean, _opts: unknown) {}
  }
  class Scene {
    render = vi.fn()
    dispose = vi.fn()
    pointerX = 10
    pointerY = 20
    clearColor: unknown = null
    ambientColor: unknown = null
    onPointerObservable = { add: vi.fn((fn: (pi: { type: number }) => void) => { capturedPointerHandler = fn }) }
    pick = vi.fn().mockReturnValue({ pickedMesh: null })
    constructor(_engine: unknown) {}
  }
  return { Color3, Color4, Engine, Scene, PointerEventTypes: { POINTERUP: 4, POINTERMOVE: 8 } }
})

// ── Sub-module mocks (no GPU needed) ─────────────────────────────────────────
vi.mock('../camera', () => ({ createIsometricCamera: vi.fn(), updateCameraForNetwork: vi.fn() }))
vi.mock('../ground', () => ({ createGround: vi.fn() }))
vi.mock('../lighting', () => ({ createSceneLighting: vi.fn() }))

const mockUpdateNetwork = vi.fn()
const mockUpdateViolations = vi.fn()
const mockDisposeAll = vi.fn()

vi.mock('../meshes/MeshRegistry', () => ({
  MeshRegistry: vi.fn().mockImplementation(() => ({
    updateNetwork: mockUpdateNetwork,
    updateViolations: mockUpdateViolations,
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
  elementId: 'b1', elementType: 'BUS', violationType: 'VOLTAGE_HIGH', value: 1.15, limit: 1.05,
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

  it('updateViolations calls MeshRegistry.updateViolations (fast path)', () => {
    const violations = [makeViolation()]
    manager.updateViolations(violations)
    expect(mockUpdateViolations).toHaveBeenCalledWith(violations)
    expect(mockUpdateNetwork).not.toHaveBeenCalled()
  })

  it('updateNetwork(null) passes null + empty violations to MeshRegistry', () => {
    // Prime with some violations first
    manager.updateViolations([makeViolation()])
    vi.clearAllMocks()
    manager.updateNetwork(null)
    expect(mockUpdateNetwork).toHaveBeenCalledWith(null, [])
  })

  it('updateNetwork(null) clears cached violations', () => {
    manager.updateViolations([makeViolation()])
    manager.updateNetwork(null)
    vi.clearAllMocks()
    // Now update with a real network — violations should be empty (cleared by null)
    manager.updateNetwork(makeNetwork())
    expect(mockUpdateNetwork).toHaveBeenCalledWith(expect.anything(), [])
  })

  it('dispose calls meshRegistry.disposeAll before scene and engine disposal', () => {
    const callOrder: string[] = []
    mockDisposeAll.mockImplementation(() => callOrder.push('meshRegistry'))
    manager.dispose()
    expect(mockDisposeAll).toHaveBeenCalledOnce()
    expect(callOrder[0]).toBe('meshRegistry')
  })

  it('calls onElementSelected with mesh metadata on POINTERUP', () => {
    const onElementSelected = vi.fn()
    const canvas = makeCanvas()
    const m = new SceneManager(canvas, onElementSelected)
    const meta = { elementType: 'GENERATOR', elementId: 'g1' }
    // Simulate the scene returning a picked mesh with metadata
    ;(m.scene.pick as ReturnType<typeof vi.fn>).mockReturnValue({ pickedMesh: { metadata: meta } })
    capturedPointerHandler?.({ type: 4 }) // POINTERUP = 4
    expect(onElementSelected).toHaveBeenCalledWith(meta)
  })

  it('calls onElementSelected with null when no mesh is picked', () => {
    const onElementSelected = vi.fn()
    const canvas = makeCanvas()
    new SceneManager(canvas, onElementSelected)
    // pick returns no mesh
    capturedPointerHandler?.({ type: 4 })
    expect(onElementSelected).toHaveBeenCalledWith(null)
  })

  // ── hover tooltip picking (#395) ────────────────────────────────────────────

  it('calls onElementHover with mesh metadata and pointer position on POINTERMOVE', () => {
    const onElementSelected = vi.fn()
    const onElementHover = vi.fn()
    const canvas = makeCanvas()
    const m = new SceneManager(canvas, onElementSelected, onElementHover)
    const meta = { elementType: 'LINE', elementId: 'l1' }
    ;(m.scene.pick as ReturnType<typeof vi.fn>).mockReturnValue({ pickedMesh: { metadata: meta } })
    capturedPointerHandler?.({ type: 8 }) // POINTERMOVE = 8
    expect(onElementHover).toHaveBeenCalledWith(meta, 10, 20) // mocked scene.pointerX/Y
  })

  it('calls onElementHover with null when no mesh is picked on POINTERMOVE', () => {
    const onElementHover = vi.fn()
    const canvas = makeCanvas()
    new SceneManager(canvas, undefined, onElementHover)
    capturedPointerHandler?.({ type: 8 })
    expect(onElementHover).toHaveBeenCalledWith(null, 10, 20)
  })

  it('does not call onElementHover on POINTERUP', () => {
    const onElementHover = vi.fn()
    const canvas = makeCanvas()
    new SceneManager(canvas, undefined, onElementHover)
    capturedPointerHandler?.({ type: 4 })
    expect(onElementHover).not.toHaveBeenCalled()
  })
})
