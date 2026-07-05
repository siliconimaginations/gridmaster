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
  activePowerMw: number
  maxActivePowerMw: number
  committed: boolean
  fuelType: string
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
}

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
  | { commandType: 'SetGeneratorOutput'; payload: { generatorId: string; activePowerMw: number } }
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
