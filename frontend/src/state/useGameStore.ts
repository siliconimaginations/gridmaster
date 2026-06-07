import { create } from 'zustand'
import { WsClient } from '../api/wsClient'
import type {
  AlertDto,
  ClockState,
  ConnectionStatus,
  EventCardDto,
  GameStateUpdate,
  GridNetworkDto,
  PlayerCommandMessage,
  PowerFlowStatus,
  ViolationDto,
} from '../api/types'

// ── Store shape ───────────────────────────────────────────────────────────────

interface GameStore {
  // Network slice
  network: GridNetworkDto | null
  powerFlowStatus: PowerFlowStatus | null
  violations: ViolationDto[]

  // Clock slice
  tickNumber: number
  gameTimeMinutes: number
  clockState: ClockState
  clockSpeedMultiplier: number

  // Alert slice
  alerts: AlertDto[]
  pendingEventCards: EventCardDto[]

  // Connection slice
  connectionStatus: ConnectionStatus
  sessionId: string | null

  // Actions
  applyUpdate: (update: GameStateUpdate) => void
  connect: (sessionId: string, token: string) => void
  disconnect: () => void
  sendCommand: (msg: PlayerCommandMessage) => void
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Returns a copy of `obj` with all `undefined` values removed.
 * Used to apply DELTA updates without overwriting existing state with undefined.
 */
function definedFields<T extends object>(obj: Partial<T>): Partial<T> {
  return Object.fromEntries(
    Object.entries(obj).filter(([, v]) => v !== undefined),
  ) as Partial<T>
}

// ── Store ─────────────────────────────────────────────────────────────────────

let wsClient: WsClient | null = null

export const useGameStore = create<GameStore>((set, get) => ({
  // Initial state
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

  // ── applyUpdate ─────────────────────────────────────────────────────────────
  applyUpdate: (update: GameStateUpdate) => {
    if (update.type === 'FULL') {
      // FULL: replace all fields unconditionally
      set({
        tickNumber: update.tickNumber,
        gameTimeMinutes: update.gameTimeMinutes,
        clockState: update.clockState,
        clockSpeedMultiplier: update.clockSpeedMultiplier,
        network: update.network ?? null,
        powerFlowStatus: update.powerFlowStatus ?? null,
        violations: update.violations ?? [],
        alerts: update.alerts ?? [],
        pendingEventCards: update.pendingEventCards ?? [],
      })
    } else {
      // DELTA: merge only the fields present in the update
      // TODO: #101 separate always-present clock fields from optional network/alert fields
      const delta = definedFields({
        tickNumber: update.tickNumber,
        gameTimeMinutes: update.gameTimeMinutes,
        clockState: update.clockState,
        clockSpeedMultiplier: update.clockSpeedMultiplier,
        network: update.network,
        powerFlowStatus: update.powerFlowStatus,
        violations: update.violations,
        alerts: update.alerts,
        pendingEventCards: update.pendingEventCards,
      })
      set(delta)
    }
  },

  // ── connect ──────────────────────────────────────────────────────────────────
  connect: (sessionId: string, token: string) => {
    set({ sessionId, connectionStatus: 'connecting' })

    wsClient = new WsClient(
      (update) => get().applyUpdate(update),
      (status) => set({ connectionStatus: status }),
    )
    wsClient.connect(sessionId, token)
  },

  // ── disconnect ───────────────────────────────────────────────────────────────
  disconnect: () => {
    wsClient?.disconnect()
    wsClient = null
    set({ connectionStatus: 'disconnected', sessionId: null })
  },

  // ── sendCommand ──────────────────────────────────────────────────────────────
  sendCommand: (msg: PlayerCommandMessage) => {
    const { sessionId } = get()
    if (!sessionId) {
      console.warn('[useGameStore] sendCommand called without an active session')
      return
    }
    wsClient?.send(sessionId, msg)
  },
}))
