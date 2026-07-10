/**
 * TypeScript interfaces for the GridMaster WebSocket protocol and REST API.
 * Mirrors the Kotlin data classes in:
 *   - docs/engineering/10-websocket-protocol.md  (WebSocket types)
 *   - docs/engineering/12-frontend-state-api.md  (REST types)
 * Updated manually when the protocol or API contracts change.
 */

// ── Enums ─────────────────────────────────────────────────────────────────────

export type UpdateType = 'FULL' | 'DELTA'
export type PowerFlowStatus = 'CONVERGED' | 'PARTIAL' | 'NETWORK_FAILURE' | 'FAILED'
export type ClockState = 'RUNNING' | 'PAUSED' | 'SLOW' | 'STOPPED'
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'
export type GameMode = 'TUTORIAL' | 'FREE_PLAY' | 'CHALLENGE'

// ── Network model ─────────────────────────────────────────────────────────────

export interface BusDto {
  id: string
  name: string
  voltageKv: number
  voltagePu: number
  angleRad: number
  /** Region/substation this bus belongs to; null for unassigned buses. */
  substationId: string | null
  /**
   * Locational marginal price (£/MWh) at this bus (#377). Always null today —
   * the current dispatch model has no per-bus nodal formulation, so there's
   * no congestion-aware price to report yet. Deliberate placeholder pending
   * a real nodal/DC-OPF dispatch model.
   */
  lmpPerMwh?: number | null
}

export interface BranchDto {
  id: string
  fromBusId: string
  toBusId: string
  activePowerMw: number
  reactivePowerMvar: number
  loadingPercent: number
  connected: boolean
}

export interface GeneratorDto {
  id: string
  busId: string
  name: string
  /**
   * Actual active power output (MW), read back from the last power-flow solve.
   * Falls back to `setpointMw` before the first solve. Use this for anything
   * production-related (cost, chart bars) — never `setpointMw` (issue #382).
   */
  activePowerMw: number
  /**
   * Player/algorithm-settable active power setpoint (MW). Not settable for
   * WIND/SOLAR generators — see `dispatchable` (issue #382).
   */
  setpointMw: number
  maxActivePowerMw: number
  committed: boolean
  fuelType: string
  /** Real per-generator marginal cost (£/MWh) from backend GeneratorMetadata (#336). */
  marginalCostPerMwh: number
  /** False for WIND/SOLAR — the setpoint control should be disabled in the UI (issue #382). */
  dispatchable: boolean
}

export interface LoadDto {
  id: string
  busId: string
  name: string
  activePowerMw: number
  reactivePowerMvar: number
}

export interface GridNetworkDto {
  buses: BusDto[]
  branches: BranchDto[]
  generators: GeneratorDto[]
  loads: LoadDto[]
  /** System marginal cost set by the last dispatch solve (£/MWh). Null until first dispatch. */
  systemMarginalCostPerMwh?: number | null
}

// ── Violations & alerts ───────────────────────────────────────────────────────

export interface ViolationDto {
  elementId: string
  elementType: 'LINE' | 'TRANSFORMER' | 'BUS'
  violationType: 'OVERLOAD' | 'VOLTAGE_HIGH' | 'VOLTAGE_LOW'
  value: number
  limit: number
}

export interface AlertDto {
  id: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  message: string
  elementId: string | null
  timestampMs: number
  acknowledged: boolean
}

export interface EventCardDto {
  id: string
  title: string
  description: string
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  options: EventOptionDto[]
}

export interface EventOptionDto {
  id: string
  label: string
  tag: string
  costGbp: number
}

// ── WebSocket messages ────────────────────────────────────────────────────────

export interface GameStateUpdate {
  type: UpdateType
  sessionId: string
  tickNumber: number
  gameTimeMinutes: number
  clockState: ClockState
  clockSpeedMultiplier: number

  // Present on FULL; only changed fields on DELTA
  network?: GridNetworkDto
  powerFlowStatus?: PowerFlowStatus
  violations?: ViolationDto[]
  alerts?: AlertDto[]
  pendingEventCards?: EventCardDto[]
  /** Server-computed 0-100 grid health score for this tick. */
  healthScore?: number
  /**
   * Current tutorial step (1–5). Non-null only for TUTORIAL-mode sessions.
   * Null/absent for FREE_PLAY and CHALLENGE sessions.
   */
  tutorialStep?: number | null
  /**
   * Game-minutes remaining until the challenge deadline. Non-null only for
   * CHALLENGE-mode sessions; clamped to 0 once the deadline passes.
   */
  challengeTimeRemainingMinutes?: number | null
  /**
   * Current daily-load-curve multiplier for `gameTimeMinutes` (issue #383),
   * where 1.0 is the network's flat baseline load.
   */
  dailyLoadMultiplier?: number | null
  /**
   * Day-of-week demand multiplier for `gameTimeMinutes` (issue #388), where
   * 1.0 is the weekly-average baseline.
   */
  weeklyLoadMultiplier?: number | null
  /**
   * Monthly seasonal demand multiplier for `gameTimeMinutes` (issue #388),
   * where 1.0 is the annual-average baseline.
   */
  seasonalLoadMultiplier?: number | null
  /**
   * Compounding year-over-year demand growth multiplier for `gameTimeMinutes`
   * (issue #388) — not normalized to average 1.0, since it's an intentional
   * long-run increase.
   */
  annualGrowthMultiplier?: number | null
  /**
   * Human-readable in-game calendar summary for `gameTimeMinutes` (issue #388),
   * e.g. "Year 2 · Day 41 · Wed · Mar".
   */
  calendarSummary?: string | null
  /**
   * Current simulated weather state (issue #391), driving WIND/SOLAR generator
   * output. Null/absent if weather is disabled server-side.
   */
  weatherState?: WeatherState | null
  /** Current cloud-cover percent (0-100) for `weatherState`. */
  weatherCloudCoverPct?: number | null
  /** Current wind speed in m/s for `weatherState`. */
  weatherWindSpeedMps?: number | null
  /** Region/zone id this weather reading applies to (issue #391), defaults to "global". */
  weatherRegionId?: string | null
}

/** Discrete weather states (issue #391) — see backend `WeatherState`. */
export type WeatherState = 'CLEAR' | 'PARTLY_CLOUDY' | 'CLOUDY' | 'OVERCAST' | 'STORM' 

/** Sent in a ConnectionStatus message when type === 'GAME_OVER'. */
export interface GameOverDto {
  finalHealthScore: number
  gridTimeManagedMinutes: number
  averageHealthScore: number
  eventsHandledCount: number
  /** True when the player won (challenge victory); false for defeat. */
  won?: boolean
}

/**
 * Commands sent from the frontend to the backend over WebSocket.
 *
 * Typed as a discriminated union so the compiler enforces the correct payload
 * shape for each commandType. Closes #99.
 */
export type PlayerCommandMessage =
  | { commandType: 'CommitGenerator'; payload: { generatorId: string } }
  | { commandType: 'DecommitGenerator'; payload: { generatorId: string } }
  // Field name must be targetMw — matches PlayerCommand.SetGeneratorOutput.targetMw
  // on the backend (backend/.../command/CommandModels.kt). A prior mismatch here
  // (activePowerMw) meant every dispatch command was rejected with "'targetMw'
  // must be a number" (#365).
  | { commandType: 'SetGeneratorOutput'; payload: { generatorId: string; targetMw: number } }
  | { commandType: 'RunUnitCommitment'; payload: { hourlyForecastMw: number[] } }
  | { commandType: 'RespondToEventCard'; payload: { cardId: string; optionId: string } }
  | { commandType: 'PauseClock'; payload: Record<string, never> }
  | { commandType: 'ResumeClock'; payload: Record<string, never> }
  | { commandType: 'SetClockSpeed'; payload: { multiplier: number } }

/** Acknowledgement returned on /user/queue/session/{sessionId}/ack after a command. */
export interface CommandAck {
  commandType: string
  success: boolean
  rejectionReason: string | null
  appliedAtTick: number
}

// ── REST: Auth ────────────────────────────────────────────────────────────────

export interface IssueTokenRequest {
  /** Stable player UUID from a previous token; omit on first launch. */
  userId?: string
}

export interface TokenResponse {
  token: string
  userId: string
  expiresInDays: number
}

// ── REST: Sessions ────────────────────────────────────────────────────────────

export interface CreateSessionRequest {
  displayName: string
  mode?: GameMode
  /** Must match a key in PresetNetworkFactory.knownPresets (e.g. "ieee14"). */
  networkPreset?: string
}

export interface SessionSummaryDto {
  id: string
  mode: GameMode
  displayName: string
  gameTimeEpochMinutes: number
  clockState: ClockState
  updatedAt: string
}

export interface SessionDetailDto {
  id: string
  userId: string
  mode: GameMode
  displayName: string
  gameTimeEpochMinutes: number
  clockState: ClockState
  clockSpeedMultiplier: number
  createdAt: string
  updatedAt: string
  completedAt: string | null
  availablePresets: string[]
}

// ── REST: Clock ───────────────────────────────────────────────────────────────

export interface ClockStatusResponse {
  clockState: ClockState
  speedMultiplier: number
  gameTimeMinutes: number
  tickCount: number
  autoSlowed: boolean
}

// ── REST: Network mutations ───────────────────────────────────────────────────

/**
 * A single network mutation sent to POST /network/mutations.
 *
 * type values: "SET_GENERATOR_OUTPUT" | "SET_GENERATOR_VOLTAGE" |
 *   "TRIP_LINE" | "CONNECT_LINE" | "TRIP_GENERATOR" | "CONNECT_GENERATOR" |
 *   "SET_TAP_POSITION" | "SET_LOAD_ACTIVE_POWER" | "SET_SHUNT_SECTION_COUNT"
 */
export interface NetworkMutationDto {
  type: string
  targetId: string
  parameters?: Record<string, unknown>
}


// ── Scene element selection ───────────────────────────────────────────────────

/** Identifies the type of a grid element displayed in the 3D scene. */
export type SceneElementType = 'GENERATOR' | 'LINE' | 'BUS' | 'LOAD'

/** Payload set on Babylon.js mesh `metadata` and stored in the Zustand store when a scene element is selected. */
export interface SelectedElementInfo {
  elementType: SceneElementType
  elementId: string
}
// ── REST: Contingency ─────────────────────────────────────────────────────────

/** A single post-contingency limit violation, from GET /contingency/{branchId}. */
export interface ContingencyViolationResult {
  /** ID of the violating equipment (line, transformer, or bus). */
  equipmentId: string
  /** Equipment type name, e.g. "LINE" or "BUS". */
  equipmentType: string
  /** Kind of limit exceeded. */
  violationType: 'THERMAL' | 'VOLTAGE_LOW' | 'VOLTAGE_HIGH'
  /** Actual value: current in Amperes (thermal) or voltage in pu (voltage). */
  value: number
  /** Applicable limit the value exceeded. */
  limit: number
  /** Loading as a percentage of the limit. */
  loadingPercent: number
  /** Severity name, e.g. "WARNING" or "CRITICAL". */
  severity: string
}

/** Post-contingency impact of losing one branch (N-1), from GET /contingency/{branchId}. */
export interface ContingencyBranchResult {
  /** ID of the matched contingency, e.g. "N1-LINE-L7". */
  contingencyId: string
  /** Post-contingency network status for this outage. */
  status: 'SECURE' | 'VIOLATION' | 'NETWORK_FAILURE'
  /** Violations that would appear if this branch tripped; empty when SECURE. */
  violations: ContingencyViolationResult[]
  /** ISO-8601 completion timestamp of the analysis run. */
  analysisCompletedAt: string
}

// ── REST: Dispatch ────────────────────────────────────────────────────────────

export interface DispatchRequest {
  totalLoadMw: number
  mode?: 'MERIT_ORDER' | 'OPTIMAL_POWER_FLOW' | 'GREEDY'
  reserveMarginFraction?: number
  securityConstrained?: boolean
}

export interface UnitCommitmentRequest {
  /** Exactly 24 hourly load forecast values in MW. */
  hourlyForecastMw: number[]
}

// ── REST: History ─────────────────────────────────────────────────────────────

/**
 * One rolling-history sample from `GET /api/sessions/{id}/history` (issue #392).
 *
 * `gameTimeMinutes` is simulated game time (the game clock), not wall-clock
 * time — the Dispatch Panel's 24h/48h/72h/week/month range selector all
 * operate on this field.
 */
export interface HistorySampleDto {
  gameTimeMinutes: number
  totalLoadMw: number
  totalGenerationMw: number
}

