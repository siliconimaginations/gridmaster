import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { CommandAck, GameStateUpdate, GridNetworkDto } from '../../api/types'

// Mock WsClient so store tests don't need a real STOMP connection.
// Capture the onAck callback so tests can simulate server ack responses.
let capturedOnAck: ((ack: CommandAck) => void) | null = null
const mockSend = vi.fn()

vi.mock('../../api/wsClient', () => ({
  WsClient: vi.fn().mockImplementation(
    (_onMsg: unknown, _onStatus: unknown, onAck: (ack: CommandAck) => void) => {
      capturedOnAck = onAck
      return { connect: vi.fn(), send: mockSend, disconnect: vi.fn() }
    },
  ),
}))

// Mock getNetwork so rollback tests can verify it is called.
vi.mock('../../api/restClient', () => ({
  getNetwork: vi.fn(),
}))

import { getNetwork } from '../../api/restClient'
import { useGameStore } from '../useGameStore'

/** Minimal GridNetworkDto fixture. */
const makeNetwork = (id = 'net1'): GridNetworkDto => ({
  buses: [{ id, name: id, voltageKv: 220, voltagePu: 1.0, angleRad: 0, substationId: 's1' }],
  branches: [],
  generators: [
    { id: 'gen1', busId: id, name: 'Gen1', activePowerMw: 100, maxActivePowerMw: 200, committed: true, fuelType: 'GAS' },
  ],
  loads: [],
})

const FULL_UPDATE: GameStateUpdate = {
  type: 'FULL',
  sessionId: 'sess1',
  tickNumber: 5,
  gameTimeMinutes: 100,
  clockState: 'RUNNING',
  clockSpeedMultiplier: 10,
  network: makeNetwork(),
  powerFlowStatus: 'CONVERGED',
  violations: [],
  alerts: [],
  pendingEventCards: [],
}

const resetStore = () =>
  useGameStore.setState({
    network: null,
    powerFlowStatus: null,
    violations: [],
    tickNumber: 0,
    gameTimeMinutes: 0,
    clockState: 'STOPPED',
    clockSpeedMultiplier: 1,
    alerts: [],
    pendingEventCards: [],
    connectionStatus: 'disconnected',
    sessionId: null,
  })

// ── applyUpdate FULL ──────────────────────────────────────────────────────────

describe('useGameStore — applyUpdate FULL', () => {
  beforeEach(resetStore)

  it('replaces clock fields', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    const s = useGameStore.getState()
    expect(s.tickNumber).toBe(5)
    expect(s.gameTimeMinutes).toBe(100)
    expect(s.clockState).toBe('RUNNING')
    expect(s.clockSpeedMultiplier).toBe(10)
  })

  it('replaces network', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    expect(useGameStore.getState().network).toEqual(makeNetwork())
  })

  it('sets network to null when FULL update omits network', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    useGameStore.getState().applyUpdate({ ...FULL_UPDATE, network: undefined })
    expect(useGameStore.getState().network).toBeNull()
  })
})

// ── applyUpdate DELTA ─────────────────────────────────────────────────────────

describe('useGameStore — applyUpdate DELTA', () => {
  beforeEach(() => {
    useGameStore.setState({
      network: makeNetwork('original'),
      powerFlowStatus: 'CONVERGED',
      violations: [],
      tickNumber: 3,
      gameTimeMinutes: 60,
      clockState: 'RUNNING',
      clockSpeedMultiplier: 1,
      alerts: [],
      pendingEventCards: [],
      connectionStatus: 'connected',
      sessionId: 'sess1',
    })
  })

  it('updates only present fields', () => {
    useGameStore.getState().applyUpdate({
      type: 'DELTA',
      sessionId: 'sess1',
      tickNumber: 4,
      gameTimeMinutes: 70,
      clockState: 'RUNNING',
      clockSpeedMultiplier: 1,
    })
    const s = useGameStore.getState()
    expect(s.tickNumber).toBe(4)
    expect(s.network).toEqual(makeNetwork('original')) // unchanged
    expect(s.powerFlowStatus).toBe('CONVERGED') // unchanged
  })

  it('updates network when included in DELTA', () => {
    const newNet = makeNetwork('updated')
    useGameStore.getState().applyUpdate({
      type: 'DELTA',
      sessionId: 'sess1',
      tickNumber: 4,
      gameTimeMinutes: 70,
      clockState: 'RUNNING',
      clockSpeedMultiplier: 1,
      network: newNet,
    })
    expect(useGameStore.getState().network).toEqual(newNet)
  })
})

// ── sendCommand ───────────────────────────────────────────────────────────────

describe('useGameStore — sendCommand', () => {
  beforeEach(() => {
    resetStore()
    vi.clearAllMocks()
    capturedOnAck = null
  })

  it('warns when no session is active', () => {
    useGameStore.setState({ sessionId: null })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    useGameStore.getState().sendCommand({ commandType: 'PauseClock', payload: {} })
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('sendCommand called without'))
    warn.mockRestore()
  })
})

// ── sendCommandOptimistic ─────────────────────────────────────────────────────

describe('useGameStore — sendCommandOptimistic', () => {
  beforeEach(() => {
    resetStore()
    vi.clearAllMocks()
    capturedOnAck = null
  })

  it('warns when no session is active', () => {
    useGameStore.setState({ sessionId: null })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    useGameStore.getState().sendCommandOptimistic({ commandType: 'CommitGenerator', payload: {} })
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('sendCommandOptimistic called without'))
    warn.mockRestore()
  })

  it('applies optimistic fn immediately when network is present', () => {
    useGameStore.getState().connect('sess1', 'token')
    useGameStore.setState({ network: makeNetwork() })

    useGameStore.getState().sendCommandOptimistic(
      { commandType: 'DecommitGenerator', payload: { generatorId: 'gen1' } },
      (prev) => ({
        ...prev,
        generators: prev.generators.map((g) => (g.id === 'gen1' ? { ...g, committed: false } : g)),
      }),
    )

    const gen = useGameStore.getState().network?.generators.find((g) => g.id === 'gen1')
    expect(gen?.committed).toBe(false)
  })

  it('sends the command via WsClient', () => {
    useGameStore.getState().connect('sess1', 'token')
    useGameStore.setState({ network: makeNetwork() })

    const msg = { commandType: 'CommitGenerator', payload: { generatorId: 'gen1' } }
    useGameStore.getState().sendCommandOptimistic(msg)

    expect(mockSend).toHaveBeenCalledWith(msg)
  })

  it('fetches authoritative network on failed ack (rollback)', async () => {
    const authoritative = makeNetwork('server')
    vi.mocked(getNetwork).mockResolvedValue(authoritative)

    useGameStore.getState().connect('sess1', 'token')
    useGameStore.setState({ network: makeNetwork('optimistic'), sessionId: 'sess1' })

    // Simulate a failed ack arriving from the server
    const failedAck: CommandAck = {
      commandType: 'CommitGenerator',
      success: false,
      rejectionReason: 'Generator already committed',
      appliedAtTick: 3,
    }
    capturedOnAck?.(failedAck)

    // Allow the async getNetwork call to resolve
    await vi.waitFor(() => expect(getNetwork).toHaveBeenCalledWith('sess1'))
    await vi.waitFor(() => expect(useGameStore.getState().network).toEqual(authoritative))
  })

  it('sets network to null when rollback fetch fails', async () => {
    vi.mocked(getNetwork).mockRejectedValue(new Error('network error'))
    vi.spyOn(console, 'error').mockImplementation(() => {})

    useGameStore.getState().connect('sess1', 'token')
    useGameStore.setState({ network: makeNetwork('optimistic'), sessionId: 'sess1' })

    const failedAck: CommandAck = {
      commandType: 'CommitGenerator',
      success: false,
      rejectionReason: 'Rejected',
      appliedAtTick: 5,
    }
    capturedOnAck?.(failedAck)

    await vi.waitFor(() => expect(getNetwork).toHaveBeenCalledWith('sess1'))
    await vi.waitFor(() => expect(useGameStore.getState().network).toBeNull())
  })

  it('does not fetch network on successful ack', () => {
    useGameStore.getState().connect('sess1', 'token')
    useGameStore.setState({ network: makeNetwork(), sessionId: 'sess1' })

    const successAck: CommandAck = {
      commandType: 'CommitGenerator',
      success: true,
      rejectionReason: null,
      appliedAtTick: 4,
    }
    capturedOnAck?.(successAck)

    expect(getNetwork).not.toHaveBeenCalled()
  })
})
