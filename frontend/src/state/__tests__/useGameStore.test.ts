import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GameStateUpdate, GridNetworkDto } from '../../api/types'

// Mock WsClient so the store tests don't need a real STOMP connection
vi.mock('../../api/wsClient', () => ({
  WsClient: vi.fn().mockImplementation(() => ({
    connect: vi.fn(),
    send: vi.fn(),
    disconnect: vi.fn(),
  })),
}))

import { useGameStore } from '../useGameStore'

/** Minimal GridNetworkDto for test fixtures. */
const makeNetwork = (id = 'net1'): GridNetworkDto => ({
  buses: [{ id, name: id, voltageKv: 220, voltagePu: 1.0, angleRad: 0, substationId: 's1' }],
  branches: [],
  generators: [],
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

describe('useGameStore — applyUpdate FULL', () => {
  beforeEach(() => {
    // Reset to initial state before each test
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
  })

  it('replaces clock fields on FULL update', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    const s = useGameStore.getState()
    expect(s.tickNumber).toBe(5)
    expect(s.gameTimeMinutes).toBe(100)
    expect(s.clockState).toBe('RUNNING')
    expect(s.clockSpeedMultiplier).toBe(10)
  })

  it('replaces network on FULL update', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    expect(useGameStore.getState().network).toEqual(makeNetwork())
  })

  it('replaces powerFlowStatus on FULL update', () => {
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    expect(useGameStore.getState().powerFlowStatus).toBe('CONVERGED')
  })

  it('sets network to null when FULL update omits network', () => {
    // First apply a full update with a network
    useGameStore.getState().applyUpdate(FULL_UPDATE)
    // Then apply a full update without network
    useGameStore.getState().applyUpdate({ ...FULL_UPDATE, network: undefined })
    expect(useGameStore.getState().network).toBeNull()
  })
})

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

  it('updates only present fields on DELTA', () => {
    const delta: GameStateUpdate = {
      type: 'DELTA',
      sessionId: 'sess1',
      tickNumber: 4,
      gameTimeMinutes: 70,
      clockState: 'RUNNING',
      clockSpeedMultiplier: 1,
      // network, powerFlowStatus, violations etc. intentionally absent
    }
    useGameStore.getState().applyUpdate(delta)
    const s = useGameStore.getState()
    expect(s.tickNumber).toBe(4)
    expect(s.gameTimeMinutes).toBe(70)
    // Network should be unchanged (DELTA didn't include it)
    expect(s.network).toEqual(makeNetwork('original'))
    expect(s.powerFlowStatus).toBe('CONVERGED')
  })

  it('updates network when DELTA includes it', () => {
    const newNet = makeNetwork('updated')
    const delta: GameStateUpdate = {
      type: 'DELTA',
      sessionId: 'sess1',
      tickNumber: 4,
      gameTimeMinutes: 70,
      clockState: 'RUNNING',
      clockSpeedMultiplier: 1,
      network: newNet,
    }
    useGameStore.getState().applyUpdate(delta)
    expect(useGameStore.getState().network).toEqual(newNet)
  })
})

describe('useGameStore — sendCommand', () => {
  it('logs a warning when no session is active', () => {
    useGameStore.setState({ sessionId: null })
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    useGameStore.getState().sendCommand({ commandType: 'PauseClock', payload: {} })
    expect(warn).toHaveBeenCalledWith(expect.stringContaining('sendCommand called without'))
    warn.mockRestore()
  })
})
