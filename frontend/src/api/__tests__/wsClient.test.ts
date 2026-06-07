import { beforeEach, describe, expect, it, vi } from 'vitest'

// ── Mock @stomp/stompjs ────────────────────────────────────────────────────

const mockPublish = vi.fn()
const mockActivate = vi.fn()
const mockDeactivate = vi.fn().mockResolvedValue(undefined)

let capturedOnConnect: (() => void) | null = null
/** topic → handler map so tests can trigger any subscription. */
const capturedSubscribers = new Map<string, (frame: { body: string }) => void>()
let capturedMockInstance: { connectHeaders: Record<string, string> } | null = null

vi.mock('@stomp/stompjs', () => ({
  Client: vi.fn().mockImplementation((config: {
    onConnect: () => void
    onDisconnect: () => void
  }) => {
    capturedOnConnect = config.onConnect

    const instance = {
      connectHeaders: {} as Record<string, string>,
      connected: true,
      activate: mockActivate,
      deactivate: mockDeactivate,
      subscribe: (dest: string, handler: (frame: { body: string }) => void) => {
        capturedSubscribers.set(dest, handler)
      },
      publish: mockPublish,
    }
    capturedMockInstance = instance
    return instance
  }),
}))

vi.mock('sockjs-client', () => ({ default: vi.fn() }))

import { WsClient } from '../wsClient'

describe('WsClient', () => {
  let onMessage: ReturnType<typeof vi.fn>
  let onStatus: ReturnType<typeof vi.fn>
  let onAck: ReturnType<typeof vi.fn>
  let client: WsClient

  beforeEach(() => {
    vi.clearAllMocks()
    capturedOnConnect = null
    capturedSubscribers.clear()
    capturedMockInstance = null
    onMessage = vi.fn()
    onStatus = vi.fn()
    onAck = vi.fn()
    client = new WsClient(onMessage, onStatus, onAck)
  })

  it('calls activate and sets status to "connecting" on connect()', () => {
    client.connect('sess1', 'token123')
    expect(onStatus).toHaveBeenCalledWith('connecting')
    expect(mockActivate).toHaveBeenCalledOnce()
  })

  it('sets Authorization header from token', () => {
    client.connect('sess1', 'mytoken')
    expect(capturedMockInstance?.connectHeaders).toEqual({ Authorization: 'Bearer mytoken' })
  })

  it('subscribes to state topic on STOMP connect', () => {
    client.connect('sess1', 'token123')
    capturedOnConnect?.()
    expect(capturedSubscribers.has('/topic/session/sess1/state')).toBe(true)
  })

  it('subscribes to ack queue on STOMP connect', () => {
    client.connect('sess1', 'token123')
    capturedOnConnect?.()
    expect(capturedSubscribers.has('/user/queue/session/sess1/ack')).toBe(true)
  })

  it('calls onStatus("connected") after STOMP connect', () => {
    client.connect('sess1', 'token123')
    capturedOnConnect?.()
    expect(onStatus).toHaveBeenCalledWith('connected')
  })

  it('forwards parsed GameStateUpdate to onMessage', () => {
    const update = { type: 'FULL', sessionId: 'sess1', tickNumber: 1, gameTimeMinutes: 0, clockState: 'RUNNING', clockSpeedMultiplier: 1 }
    client.connect('sess1', 'token')
    capturedOnConnect?.()
    capturedSubscribers.get('/topic/session/sess1/state')?.({ body: JSON.stringify(update) })
    expect(onMessage).toHaveBeenCalledWith(update)
  })

  it('forwards parsed CommandAck to onAck', () => {
    const ack = { commandType: 'CommitGenerator', success: true, rejectionReason: null, appliedAtTick: 7 }
    client.connect('sess1', 'token')
    capturedOnConnect?.()
    capturedSubscribers.get('/user/queue/session/sess1/ack')?.({ body: JSON.stringify(ack) })
    expect(onAck).toHaveBeenCalledWith(ack)
  })

  it('does not call onMessage on malformed JSON', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    client.connect('sess1', 'token')
    capturedOnConnect?.()
    capturedSubscribers.get('/topic/session/sess1/state')?.({ body: 'not-json' })
    expect(onMessage).not.toHaveBeenCalled()
  })

  it('does not call onAck on malformed JSON', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    client.connect('sess1', 'token')
    capturedOnConnect?.()
    capturedSubscribers.get('/user/queue/session/sess1/ack')?.({ body: 'bad' })
    expect(onAck).not.toHaveBeenCalled()
  })

  it('publishes to the correct destination on send()', () => {
    client.connect('sess1', 'token')
    capturedOnConnect?.()
    client.send({ commandType: 'PauseClock', payload: {} })
    expect(mockPublish).toHaveBeenCalledWith({
      destination: '/app/session/sess1/command',
      body: JSON.stringify({ commandType: 'PauseClock', payload: {} }),
    })
  })

  it('calls deactivate on disconnect()', async () => {
    client.disconnect()
    expect(mockDeactivate).toHaveBeenCalledOnce()
  })
})
