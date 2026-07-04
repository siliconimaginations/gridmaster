import { Client, IMessage } from '@stomp/stompjs'
import SockJS from 'sockjs-client'
import type { CommandAck, ConnectionStatus, GameOverDto, GameStateUpdate, PlayerCommandMessage } from './types'

/** Server-initiated lifecycle message (distinct from the transport ConnectionStatus). */
export interface ServerStatusMessage {
  type: 'CONNECTED' | 'RECONNECTED' | 'SESSION_NOT_FOUND' | 'AUTH_FAILED' | 'GAME_OVER'
  sessionId?: string
  missedTicks?: number
  gameOver?: GameOverDto
}

const SERVER_STATUS_TYPES = new Set(['CONNECTED', 'RECONNECTED', 'SESSION_NOT_FOUND', 'AUTH_FAILED', 'GAME_OVER'])

// Relative /ws path lets Vite's proxy forward the WebSocket to localhost:8080 in dev.
// Set VITE_WS_URL for production deployments.
const WS_URL = (import.meta.env.VITE_WS_URL as string | undefined) ?? '/ws'

/** Maximum number of commands queued while disconnected. */
const MAX_QUEUE_SIZE = 10

/**
 * Wraps `@stomp/stompjs` with a SockJS factory for environments that
 * block raw WebSocket.
 *
 * Subscribes to two server-push destinations:
 * - `/topic/session/{sessionId}/state`  — `GameStateUpdate` on each tick
 * - `/user/queue/session/{sessionId}/ack` — `CommandAck` after each command
 *
 * Lifecycle is managed by the Zustand store — do not use this class
 * directly in React components. Use `useGameStore` actions instead.
 */
export class WsClient {
  private client: Client
  private sessionId: string | null = null
  private commandQueue: PlayerCommandMessage[] = []

  constructor(
    private readonly onMessage: (update: GameStateUpdate) => void,
    private readonly onStatus: (status: ConnectionStatus) => void,
    private readonly onAck: (ack: CommandAck) => void,
    private readonly onServerStatus?: (msg: ServerStatusMessage) => void,
  ) {
    this.client = new Client({
      webSocketFactory: () => new SockJS(WS_URL) as WebSocket,
      reconnectDelay: 2000,
      onConnect: () => this._onConnected(),
      onDisconnect: () => this.onStatus('disconnected'),
      onStompError: () => this.onStatus('disconnected'),
      onWebSocketError: () => this.onStatus('reconnecting'),
    })
  }

  /** Connect to the server and subscribe to the session state topic. */
  connect(sessionId: string, token: string): void {
    this.sessionId = sessionId
    this.onStatus('connecting')

    this.client.connectHeaders = {
      Authorization: `Bearer ${token}`,
    }

    this.client.activate()
  }

  /** Send a command to the server. Queues if not yet connected. */
  send(msg: PlayerCommandMessage): void {
    if (!this.sessionId) return
    if (this.client.connected) {
      this.client.publish({
        destination: `/app/session/${this.sessionId}/command`,
        body: JSON.stringify(msg),
      })
    } else {
      // Queue command; flush on reconnect
      if (this.commandQueue.length < MAX_QUEUE_SIZE) {
        this.commandQueue.push(msg)
      }
    }
  }

  /** Disconnect and clean up. */
  async disconnect(): Promise<void> {
    this.sessionId = null
    this.commandQueue = []
    try {
      await this.client.deactivate()
    } catch {
      // deactivate rejects only if already inactive — safe to ignore
    }
  }

  // ── Private ────────────────────────────────────────────────────────────────

  private _onConnected(): void {
    if (!this.sessionId) return

    this.client.subscribe(`/topic/session/${this.sessionId}/state`, (frame: IMessage) => {
      try {
        const msg = JSON.parse(frame.body) as { type: string }
        if (SERVER_STATUS_TYPES.has(msg.type)) {
          this.onServerStatus?.(msg as ServerStatusMessage)
        } else {
          this.onMessage(msg as GameStateUpdate)
        }
      } catch (err) {
        console.error('[WsClient] Failed to parse state message', err)
      }
    })

    // Subscribe to the user-specific ack queue for CommandAck responses.
    // Spring routes replies to /user/queue/... based on the authenticated principal.
    this.client.subscribe(`/user/queue/session/${this.sessionId}/ack`, (frame: IMessage) => {
      try {
        const ack = JSON.parse(frame.body) as CommandAck
        this.onAck(ack)
      } catch (err) {
        console.error('[WsClient] Failed to parse CommandAck', err)
      }
    })

    this.onStatus('connected')
    this._flushQueue()
  }

  private _flushQueue(): void {
    const queued = this.commandQueue.splice(0)
    for (const msg of queued) {
      this.send(msg)
    }
  }
}
