/**
 * TypeScript interfaces for the GridMaster WebSocket protocol.
 * Mirrors the Kotlin data classes documented in docs/engineering/10-websocket-protocol.md.
 * Updated manually when the protocol doc changes.
 */

// ── Enums ────────────────────────────────────────────────────────────────────

export type UpdateType = 'FULL' | 'DELTA'
export type PowerFlowStatus = 'CONVERGED' | 'PARTIAL' | 'NETWORK_FAILURE' | 'FAILED'
export type ClockState = 'RUNNING' | 'PAUSED' | 'SLOW' | 'STOPPED'
export type ConnectionStatus = 'disconnected' | 'connecting' | 'connected' | 'reconnecting'

// ── Network model ─────────────────────────────────────────────────────────────

export interface BusDto {
  id: string
  name: string
  voltageKv: number
  voltagePu: number
  angleRad: number
  substationId: string
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
}

// TODO: #99 replace with discriminated union per commandType for compile-time payload safety
export interface PlayerCommandMessage {
  commandType: string
  payload: Record<string, unknown>
}
