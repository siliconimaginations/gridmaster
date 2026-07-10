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
  GameOverDto,
  GameStateUpdate,
  GridNetworkDto,
  PlayerCommandMessage,
  PowerFlowStatus,
  SelectedElementInfo,
  ViolationDto,
  WeatherState,
} from '../api/types'

/** Number of health-score samples kept for the TopHud sparkline (#333). */
export const HEALTH_HISTORY_SIZE = 30

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
  /**
   * Client-generated ephemeral alerts (command feedback, speed revert notices).
   * Not overwritten by server GameStateUpdates; self-managed via pushLocalAlert /
   * dismissLocalAlert. (#282, #273)
   */
  localAlerts: AlertDto[]
  /** Push a client-generated ephemeral alert. */
  pushLocalAlert: (alert: AlertDto) => void
  /** Remove a specific local alert by id (called by AlertToastContainer on dismiss/auto-expire). */
  dismissLocalAlert: (id: string) => void
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
  /** Non-null when the server has sent GAME_OVER. Shows the GameOverPanel. */
  gameOver: GameOverDto | null
  /** Server-computed 0-100 health score from the last tick. */
  healthScore: number | null
  /**
   * Ring-buffer of the last [HEALTH_HISTORY_SIZE] health-score samples,
   * oldest first. Appended on every update that carries a healthScore;
   * feeds the TopHud sparkline (#333).
   */
  healthHistory: number[]
  /** Current tutorial step (1–5); null for non-tutorial sessions. */
  tutorialStep: number | null
  /** Game-minutes remaining until challenge deadline; null for non-challenge sessions. */
  challengeTimeRemainingMinutes: number | null
  /**
   * Current daily-load-curve multiplier (issue #383), where 1.0 is the
   * network's flat baseline load. Null until the first server update arrives.
   */
  dailyLoadMultiplier: number | null
  /**
   * Day-of-week demand multiplier (issue #388), where 1.0 is the
   * weekly-average baseline. Null until the first server update arrives.
   */
  weeklyLoadMultiplier: number | null
  /**
   * Monthly seasonal demand multiplier (issue #388), where 1.0 is the
   * annual-average baseline. Null until the first server update arrives.
   */
  seasonalLoadMultiplier: number | null
  /**
   * Compounding year-over-year demand growth multiplier (issue #388). Null
   * until the first server update arrives.
   */
  annualGrowthMultiplier: number | null
  /**
   * Human-readable in-game calendar summary, e.g. "Year 2 · Day 41 · Wed · Mar"
   * (issue #388). Null until the first server update arrives.
   */
  calendarSummary: string | null
  /**
   * Current simulated weather state (issue #391). Null until the first server
   * update arrives, or if weather is disabled server-side.
   */
  weatherState: WeatherState | null
  /** Current cloud-cover percent (0-100) for `weatherState`. */
  weatherCloudCoverPct: number | null
  /** Current wind speed in m/s for `weatherState`. */
  weatherWindSpeedMps: number | null
  /** Region/zone id this weather reading applies to (issue #391), defaults to "global". */
  weatherRegionId: string | null

  // Selection slice
  selectedElement: SelectedElementInfo | null
  selectElement: (info: SelectedElementInfo | null) => void

  /**
   * Currently hovered scene element (#395) — distinct from `selectedElement`,
   * which requires a click. Drives the LineTooltip hover tooltip; updated on
   * every pointer move via SceneManager's onElementHover callback.
   */
  hoveredElement: SelectedElementInfo | null
  hoverElement: (info: SelectedElementInfo | null) => void

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
  localAlerts: [] as AlertDto[],
  pendingEventCards: [] as EventCardDto[],
  ucSchedule: null as boolean[] | null,
  gameOver: null as GameOverDto | null,
  healthScore: null as number | null,
  healthHistory: [] as number[],
  tutorialStep: null as number | null,
  challengeTimeRemainingMinutes: null as number | null,
  dailyLoadMultiplier: null as number | null,
  weeklyLoadMultiplier: null as number | null,
  seasonalLoadMultiplier: null as number | null,
  annualGrowthMultiplier: null as number | null,
  calendarSummary: null as string | null,
  weatherState: null as WeatherState | null,
  weatherCloudCoverPct: null as number | null,
  weatherWindSpeedMps: null as number | null,
  weatherRegionId: null as string | null,
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
  hoveredElement: null,

  // ── setUcSchedule ────────────────────────────────────────────────────────────
  setUcSchedule: (schedule: boolean[] | null) => {
    set({ ucSchedule: schedule })
  },

  // ── pushLocalAlert ───────────────────────────────────────────────────────────
  pushLocalAlert: (alert: AlertDto) => {
    set((state) => ({ localAlerts: [...state.localAlerts, alert] }))
  },

  // ── dismissLocalAlert ────────────────────────────────────────────────────────
  dismissLocalAlert: (id: string) => {
    set((state) => ({ localAlerts: state.localAlerts.filter((a) => a.id !== id) }))
  },

  // ── applyUpdate ─────────────────────────────────────────────────────────────
  applyUpdate: (update: GameStateUpdate) => {
    // Ring-buffer append (#333): one sample per update that carries a score.
    // FULL updates replace state but the trend history survives within a session.
    const appendHealth = (history: number[]): number[] =>
      update.healthScore != null
        ? [...history, update.healthScore].slice(-HEALTH_HISTORY_SIZE)
        : history

    if (update.type === 'FULL') {
      // FULL: replace all fields unconditionally
      set((state) => ({
        tickNumber: update.tickNumber,
        gameTimeMinutes: update.gameTimeMinutes,
        clockState: update.clockState,
        clockSpeedMultiplier: update.clockSpeedMultiplier,
        network: update.network ?? null,
        powerFlowStatus: update.powerFlowStatus ?? null,
        violations: update.violations ?? [],
        alerts: update.alerts ?? [],
        pendingEventCards: update.pendingEventCards ?? [],
        healthScore: update.healthScore ?? null,
        healthHistory: appendHealth(state.healthHistory),
        tutorialStep: update.tutorialStep ?? null,
        challengeTimeRemainingMinutes: update.challengeTimeRemainingMinutes ?? null,
        dailyLoadMultiplier: update.dailyLoadMultiplier ?? null,
        weeklyLoadMultiplier: update.weeklyLoadMultiplier ?? null,
        seasonalLoadMultiplier: update.seasonalLoadMultiplier ?? null,
        annualGrowthMultiplier: update.annualGrowthMultiplier ?? null,
        calendarSummary: update.calendarSummary ?? null,
        weatherState: update.weatherState ?? null,
        weatherCloudCoverPct: update.weatherCloudCoverPct ?? null,
        weatherWindSpeedMps: update.weatherWindSpeedMps ?? null,
        weatherRegionId: update.weatherRegionId ?? null,
      }))
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
        healthScore: update.healthScore ?? undefined,
        tutorialStep: update.tutorialStep ?? undefined,
        challengeTimeRemainingMinutes: update.challengeTimeRemainingMinutes ?? undefined,
        dailyLoadMultiplier: update.dailyLoadMultiplier ?? undefined,
        weeklyLoadMultiplier: update.weeklyLoadMultiplier ?? undefined,
        seasonalLoadMultiplier: update.seasonalLoadMultiplier ?? undefined,
        annualGrowthMultiplier: update.annualGrowthMultiplier ?? undefined,
        calendarSummary: update.calendarSummary ?? undefined,
        weatherState: update.weatherState ?? undefined,
        weatherCloudCoverPct: update.weatherCloudCoverPct ?? undefined,
        weatherWindSpeedMps: update.weatherWindSpeedMps ?? undefined,
        weatherRegionId: update.weatherRegionId ?? undefined,
      })
      set((state) => ({ ...clockUpdate, ...optionalUpdate, healthHistory: appendHealth(state.healthHistory) }))
    }
  },

  // ── connect ──────────────────────────────────────────────────────────────────
  connect: (sessionId: string, token: string) => {
    // Disconnect any existing client to prevent orphaned connections
    wsClient?.disconnect()
    set({ sessionId, connectionStatus: 'connecting' })

    // Hydrate network state immediately via REST so the scene renders even
    // if the first WebSocket message is a DELTA (which omits unchanged fields).
    getNetwork(sessionId).then((restNetwork) => {
      const s = useGameStore.getState()
      // Race-guard: apply REST hydration only when:
      //   1. The session is still active (guards against quick disconnect/reconnect).
      //   2. network is still null — meaning no WS FULL update has arrived yet.
      //
      // Two scenarios:
      //   A) REST arrives before WS FULL  → network is null  → REST hydrates (cold render works ✓)
      //      Later WS FULL overwrites with the authoritative WsDto (which has `committed`).
      //   B) WS FULL arrives before REST  → network is non-null → REST is skipped (correct ✓)
      //      Skipping is essential because REST returns the domain-model DTO (`connected`)
      //      while WS returns GridNetworkWsDto (`committed`). If REST overwrote WS the
      //      `committed` field would be stripped and AL-01 / InspectorPanel would break.
      //
      // TODO: unify REST and WS DTOs so this guard is unnecessary. Tracked: #237
      if (s.sessionId === sessionId && s.network === null) {
        useGameStore.setState({ network: restNetwork })
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
    set({ ...INITIAL_GAME_STATE, connectionStatus: 'disconnected', sessionId: null, sessionInvalidated: false, selectedElement: null, hoveredElement: null, gameOver: null, healthScore: null, healthHistory: [] })
  },

  // ── selectElement ────────────────────────────────────────────────────────────
  selectElement: (info: SelectedElementInfo | null) => {
    set({ selectedElement: info })
  },

  // ── hoverElement (#395) ──────────────────────────────────────────────────────
  hoverElement: (info: SelectedElementInfo | null) => {
    set({ hoveredElement: info })
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
 *
 * On GAME_OVER: also clears the stored session ID so a page refresh starts a
 * fresh session instead of resuming the completed one (#334).
 */
function _handleServerStatus(msg: ServerStatusMessage): void {
  if (msg.type === 'SESSION_NOT_FOUND') {
    console.warn('[useGameStore] Server reported SESSION_NOT_FOUND — re-bootstrapping')
    clearStoredSessionId()
    useGameStore.getState().disconnect()
    useGameStore.setState({ sessionInvalidated: true })
  } else if (msg.type === 'GAME_OVER' && msg.gameOver) {
    console.info('[useGameStore] Server reported GAME_OVER', msg.gameOver)
    clearStoredSessionId()
    useGameStore.setState({ gameOver: msg.gameOver, clockState: 'STOPPED' })
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
function _makeLocalAlert(
  severity: AlertDto['severity'],
  message: string,
  elementId?: string,
): AlertDto {
  return {
    id: `local-${Date.now()}-${Math.random().toString(36).slice(2, 7)}`,
    severity,
    message,
    elementId: elementId ?? null,
    timestampMs: Date.now(),
    acknowledged: false,
  }
}

function _handleAck(ack: CommandAck, get: () => GameStore): void {
  const store = useGameStore.getState()

  if (ack.success) {
    // Show an INFO toast for RunUnitCommitment so the player gets feedback that
    // the schedule was applied — server FULL updates don't contain a banner for this. (#273)
    if (ack.commandType === 'RunUnitCommitment') {
      store.pushLocalAlert(
        _makeLocalAlert('INFO', 'Unit commitment schedule applied successfully.'),
      )
    }
    return
  }

  console.warn(
    `[useGameStore] Command ${ack.commandType} rejected at tick ${ack.appliedAtTick}: ${ack.rejectionReason}`,
  )

  // Inject a WARNING toast explaining the rejection so players aren't confused by
  // silent speed reverts or other command failures. (#282)
  const reason = ack.rejectionReason ? ` — ${ack.rejectionReason}` : ''
  if (ack.commandType === 'SetClockSpeed') {
    store.pushLocalAlert(
      _makeLocalAlert('WARNING', `Clock speed change rejected${reason}. Speed was reverted.`),
    )
  } else {
    store.pushLocalAlert(
      _makeLocalAlert('WARNING', `Command failed: ${ack.commandType}${reason}`),
    )
  }

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

