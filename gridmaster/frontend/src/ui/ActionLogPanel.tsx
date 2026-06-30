import { useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { useGameStore } from '../state/useGameStore'
import type { ActionLogEntry } from '../state/actionLog'
import styles from './ActionLogPanel.module.css'

// ── Icons ─────────────────────────────────────────────────────────────────────

const SEVERITY_ICON: Record<'CRITICAL' | 'WARNING' | 'INFO', string> = {
  CRITICAL: '⚡',
  WARNING: '⚠',
  INFO: 'ℹ',
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function fmtTime(ms: number): string {
  return new Date(ms).toLocaleTimeString('en-US', { hour12: false })
}

function entryDescription(e: ActionLogEntry): { icon: string; text: string } {
  switch (e.kind) {
    case 'command':
      return {
        icon: e.success ? '▶' : '✗',
        text:
          e.commandType +
          (!e.success && e.rejectionReason ? ` — ${e.rejectionReason}` : ''),
      }
    case 'alert':
      return {
        icon: SEVERITY_ICON[e.severity],
        text: e.message,
      }
    case 'checkpoint':
      return {
        icon: '💾',
        text: e.label,
      }
  }
}

// ── ActionLogPanel ────────────────────────────────────────────────────────────

/**
 * Collapsible panel anchored to the right edge of the screen.
 * Displays the last ACTION_LOG_MAX player commands, server alerts, and
 * auto-save checkpoints, newest first.
 *
 * @see issue #196
 */
export function ActionLogPanel() {
  const [open, setOpen] = useState(false)

  const { actionLog, clearActionLog } = useGameStore(
    useShallow((s) => ({
      actionLog: s.actionLog,
      clearActionLog: s.clearActionLog,
    })),
  )

  return (
    <div className={styles.root} data-testid="action-log-panel">
      {/* Toggle tab */}
      <button
        type="button"
        className={styles.toggle}
        onClick={() => setOpen((o) => !o)}
        aria-label={open ? 'Close action log' : 'Open action log'}
        data-testid="action-log-toggle"
      >
        {open ? '→' : '←'}
      </button>

      {/* Panel */}
      {open && (
        <div className={styles.panel}>
          <div className={styles.header}>
            <h2>Action Log</h2>
            <button
              type="button"
              className={styles.clearBtn}
              onClick={clearActionLog}
              aria-label="Clear action log"
            >
              Clear
            </button>
          </div>

          <ul className={styles.list} aria-label="Action log entries">
            {actionLog.map((e) => {
              const { icon, text } = entryDescription(e)
              return (
                <li key={e.id} className={styles.entry}>
                  <span className={styles.time}>{fmtTime(e.timestampMs)}</span>
                  <span className={styles.icon} aria-hidden="true">{icon}</span>
                  <span className={styles.text} title={text}>{text}</span>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </div>
  )
}
