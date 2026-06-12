import { create } from 'zustand'
import { subscribeWithSelector } from 'zustand/middleware'
import { WsClient } from '../api/wsClient'
import type { ServerStatusMessage } from '../api/wsClient'
import { clearStoredSessionId, getNetwork } from '../api/restClient'
import type {
  AlertDto,
  ClockState,
  CommandAck,
  ConnectionStatus,
  EventCardDto,
  GameStateUpdate,
  GridNetworkDto,
  PlayerCommandMessage,
  PowerFlowStatus,
  SelectedElementInfo,
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

  /** 24-element boolean array — true if any generator is committed for that hour. Null until the player runs a UC schedule. */
  ucSchedule: boolean[] | null
  /** Updates the UC schedule displayed in the TimelineStrip. */
  setUcSchedule: (schedule: boolean[] | null) => void

  // Connection slice
  connectionStatus: ConnectionStatus
  sessionId: string | null
  /** Flipped to true when the server sends SESSION_NOT_FOUND; bootstrap re-runs on this. */
  sessionInvalidated: boolean

  // Selection slice
  selectedElement: SelectedElementInfo | null
  selectElement: (info: SelectedElementInfo | null) => void

  // Actions
  applyUpdate: (update: GameStateUpdate) => void
  connect: (sessionId: string, token: string) => void
  disconnect: () => void
  sendCommand: (msg: PlayerCommandMessage) => void
  /**
   * Sends a command over WebSocket and immediately applies `optimisticFn`
   * to the store (if provided). If the server returns a failed `CommandAck`,
   * the store is refreshed from the REST API to restore authoritative state.
   *
   * Use for commands where the likely outcome is known (e.g. CommitGenerator,
   * DecommitGenerator, SetGeneratorOutput). Omit `optimisticFn` for commands
   * whose outcome is uncertain.
   */
  sendCommandOptimistic: (
    msg: PlayerCommandMessage,
    optimisticFn?: (prev: GridNetworkDto) => GridNetworkDto,
  ) => void
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

// ── Initial state ─────────────────────────────────────────────────────────────

const INITIAL_GAME_STATE = {
  network: null,
  powerFlowStatus: null,
  violations: [] as ViolationDto[],
  tickNumber: 0,
  gameTimeMinutes: 0,
  clockState: 'STOPPED' as ClockState,
  clockSpeedMultiplier: 1,
  alerts: [] as AlertDto[],
  pendingEventCards: [] as EventCardDto[],
  ucSchedule: null as boolean[] | null,
} as const satisfies Partial<GameStore>

// ── Store ─────────────────────────────────────────────────────────────────────

let wsClient: WsClient | null = null

export const useGameStore = create<GameStore>()(subscribeWithSelector((set, get) => ({
  // Initial state
  ...INITIAL_GAME_STATE,
  connectionStatus: 'disconnected',
  sessionId: null,
  sessionInvalidated: false,
  selectedElement: null,

  // ── setUcSchedule ────────────────────────────────────────────────────────────
  setUcSchedule: (schedule: boolean[] | null) => {
    set({ ucSchedule: schedule })
  },

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
      // DELTA: clock fields are always present — set them unconditionally.
      // Network/alert fields are optional — only merge when present. Closes #101.
      const clockUpdate = {
        tickNumber: update.tickNumber,
        gameTimeMinutes: update.gameTimeMinutes,
        clockState: update.clockState,
        clockSpeedMultiplier: update.clockSpeedMultiplier,
      }
      const optionalUpdate = definedFields<GameStore>({
        network: update.network ?? undefined,
        powerFlowStatus: update.powerFlowStatus ?? undefined,
        violations: update.violations ?? undefined,
        alerts: update.alerts ?? undefined,
        pendingEventCards: update.pendingEventCards ?? undefined,
      })
      set({ ...clockUpdate, ...optionalUpdate })
    }
  },

  // ── connect ──────────────────────────────────────────────────────────────────
  connect: (sessionId: string, token: string) => {
    // Disconnect any existing client to prevent orphaned connections
    wsClient?.disconnect()
    set({ sessionId, connectionStatus: 'connecting' })

    // Hydrate network state immediately via REST so the scene renders even
    // if the first WebSocket message is a DELTA (which omits unchanged fields).
    getNetwork(sessionId).then((network) => {
      if (useGameStore.getState().sessionId === sessionId) {
        useGameStore.setState({ network })
      }
    }).catch(() => { /* session may not exist yet — WS updates will fill in */ })

    wsClient = new WsClient(
      (update) => get().applyUpdate(update),
      (status) => set({ connectionStatus: status }),
      (ack: CommandAck) => _handleAck(ack, get),
      (msg: ServerStatusMessage) => _handleServerStatus(msg),
    )
    wsClient.connect(sessionId, token)
  },

  // ── disconnect ───────────────────────────────────────────────────────────────
  disconnect: () => {
    wsClient?.disconnect()
    wsClient = null
    // Resets all game state to initial values so a future session starts clean
    set({ ...INITIAL_GAME_STATE, connectionStatus: 'disconnected', sessionId: null, sessionInvalidated: false, selectedElement: null })
  },

  // ── selectElement ────────────────────────────────────────────────────────────
  selectElement: (info: SelectedElementInfo | null) => {
    set({ selectedElement: info })
  },

  // ── sendCommand ──────────────────────────────────────────────────────────────
  sendCommand: (msg: PlayerCommandMessage) => {
    const { sessionId } = get()
    if (!sessionId) {
      console.warn('[useGameStore] sendCommand called without an active session')
      return
    }
    wsClient?.send(msg)
  },

  // ── sendCommandOptimistic ────────────────────────────────────────────────────
  sendCommandOptimistic: (
    msg: PlayerCommandMessage,
    optimisticFn?: (prev: GridNetworkDto) => GridNetworkDto,
  ) => {
    const { sessionId, network } = get()
    if (!sessionId) {
      console.warn('[useGameStore] sendCommandOptimistic called without an active session')
      return
    }

    // Apply the optimistic update immediately so the UI feels responsive.
    if (optimisticFn && network) {
      set({ network: optimisticFn(network) })
    }

    wsClient?.send(msg)
  },
})))

// ── ServerStatus handler ──────────────────────────────────────────────────────

/**
 * Called when a server-initiated ConnectionStatus message arrives on the
 * session state topic (e.g. SESSION_NOT_FOUND after a backend restart).
 *
 * On SESSION_NOT_FOUND: clears the stale stored session ID, disconnects, and
 * sets `sessionInvalidated = true` so `useSessionBootstrap` re-runs and creates
 * a fresh session automatically.
 */
function _handleServerStatus(msg: ServerStatusMessage): void {
  if (msg.type === 'SESSION_NOT_FOUND') {
    console.warn('[useGameStore] Server reported SESSION_NOT_FOUND — re-bootstrapping')
    clearStoredSessionId()
    useGameStore.getState().disconnect()
    useGameStore.setState({ sessionInvalidated: true })
  }
}

// ── CommandAck handler ────────────────────────────────────────────────────────

/**
 * Called when a CommandAck arrives on /user/queue/session/{id}/ack.
 *
 * On failure: fetches authoritative network state from REST to roll back any
 * optimistic update applied in `sendCommandOptimistic`. A full GameStateUpdate
 * from the server will also arrive soon (the server publishes one after every
 * successful command), so we only need to repair failures.
 */
function _handleAck(ack: CommandAck, get: () => GameStore): void {
  if (ack.success) return

  console.warn(
    `[useGameStore] Command ${ack.commandType} rejected at tick ${ack.appliedAtTick}: ${ack.rejectionReason}`,
  )

  const { sessionId } = get()
  if (!sessionId) return

  // Fetch authoritative network state to roll back any optimistic change.
  getNetwork(sessionId)
    .then((network) => {
      // Only apply if the session hasn't changed while the fetch was in flight.
      if (get().sessionId === sessionId) {
        useGameStore.setState({ network })
      }
    })
    .catch((err) => {
      console.error('[useGameStore] Failed to refresh network after rejected command', err)
      // Clear stale optimistic state so the UI shows "no data" rather than
      // incorrect data. The next GameStateUpdate tick will repopulate it.
      if (get().sessionId === sessionId) {
        useGameStore.setState({ network: null })
      }
    })
}
