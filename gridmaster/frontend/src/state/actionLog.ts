/**
 * Types and helpers for the player action log (issue #196).
 *
 * The log records three kinds of events:
 *  - 'command' — a player command ACK from the server
 *  - 'alert'   — a new server alert that arrived via DELTA
 *  - 'checkpoint' — an auto-save checkpoint marker
 */

/** Maximum number of entries retained in the log (FIFO eviction). */
export const ACTION_LOG_MAX = 200

let _logCounter = 0

/** Returns a short unique id for a log entry. */
export function makeLogId(): string {
  return `${Date.now()}-${Math.random().toString(36).slice(2, 7)}-${_logCounter++}`
}

// ── Entry variants ────────────────────────────────────────────────────────────

interface BaseLogEntry {
  id: string
  timestampMs: number
}

export interface CommandLogEntry extends BaseLogEntry {
  kind: 'command'
  commandType: string
  success: boolean
  rejectionReason: string | null
  /** Game tick at which the server applied (or rejected) the command. */
  tickNumber: number
}

export interface AlertLogEntry extends BaseLogEntry {
  kind: 'alert'
  severity: 'CRITICAL' | 'WARNING' | 'INFO'
  message: string
  elementId: string | null
}

export interface CheckpointLogEntry extends BaseLogEntry {
  kind: 'checkpoint'
  label: string
}

export type ActionLogEntry =
  | CommandLogEntry
  | AlertLogEntry
  | CheckpointLogEntry

/**
 * Input shape for `pushActionLogEntry` — same as ActionLogEntry but without
 * the auto-generated `id` and `timestampMs` fields.
 * Defined as an explicit union (not `Omit<ActionLogEntry, …>`) so TypeScript
 * correctly narrows the discriminated variant fields.
 */
export type ActionLogInput =
  | Omit<CommandLogEntry, 'id' | 'timestampMs'>
  | Omit<AlertLogEntry, 'id' | 'timestampMs'>
  | Omit<CheckpointLogEntry, 'id' | 'timestampMs'>
