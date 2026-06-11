import { useEffect, useMemo, useRef, useState } from 'react'
import { useShallow } from 'zustand/react/shallow'
import type { AlertDto } from '../api/types'
import { useGameStore } from '../state/useGameStore'
import styles from './AlertToast.module.css'

// ── Constants ─────────────────────────────────────────────────────────────────

/** Wall-clock auto-dismiss delay (ms) per severity. `null` = never auto-dismiss. */
const AUTO_DISMISS_MS: Record<AlertDto['severity'], number | null> = {
  CRITICAL: null,
  WARNING: 8000,
  INFO: 5000,
}

const SEVERITY_EMOJI: Record<AlertDto['severity'], string> = {
  CRITICAL: '⚡',
  WARNING: '⚠️',
  INFO: 'ℹ️',
}

/** Max toasts visible at once; extras are queued. */
const MAX_VISIBLE = 3

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Removes duplicate alerts that share the same `elementId`.
 * The most recent alert per elementId is kept.
 * Alerts with `elementId === null` are never de-duplicated.
 */
function dedupeByElementId(alerts: AlertDto[] | null | undefined): AlertDto[] {
  if (!alerts) return []
  const seen = new Set<string>()
  const result: AlertDto[] = []
  // Iterate newest-first so the latest alert per elementId wins.
  for (let i = alerts.length - 1; i >= 0; i--) {
    const a = alerts[i]
    if (a.elementId !== null) {
      if (seen.has(a.elementId)) continue
      seen.add(a.elementId)
    }
    result.unshift(a)
  }
  return result
}

// ── AlertToastContainer ───────────────────────────────────────────────────────

/**
 * Renders a stack of up to {@link MAX_VISIBLE} dismissible alert toasts in the
 * bottom-right corner of the HUD.
 *
 * Reads `alerts` from the Zustand store and applies:
 * - De-duplication by `elementId` (keeps most recent per element).
 * - User-initiated dismissal (× button).
 * - Auto-dismiss after a severity-specific wall-clock delay.
 *
 * @see docs/ux/03-alert-toasts.md
 * @see issue #85
 */
export function AlertToastContainer() {
  const alerts = useGameStore(useShallow((s) => s.alerts))
  const [dismissed, setDismissed] = useState<Set<string>>(new Set())
  const timers = useRef<Map<string, ReturnType<typeof setTimeout>>>(new Map())

  const visible = useMemo(
    () =>
      dedupeByElementId(alerts)
        .filter((a) => !dismissed.has(a.id))
        .slice(0, MAX_VISIBLE),
    [alerts, dismissed],
  )

  // Start auto-dismiss timers for newly visible alerts; prune timers for
  // alerts that have left the store entirely.
  useEffect(() => {
    const storeIds = new Set(alerts.map((a) => a.id))

    // Clear timers for alerts no longer in the store
    for (const [id, t] of timers.current.entries()) {
      if (!storeIds.has(id)) {
        clearTimeout(t)
        timers.current.delete(id)
      }
    }

    // Start timers for newly visible, auto-dismissible alerts
    for (const alert of visible) {
      if (timers.current.has(alert.id)) continue
      const delay = AUTO_DISMISS_MS[alert.severity]
      if (delay !== null) {
        const t = setTimeout(() => {
          setDismissed((prev) => new Set([...prev, alert.id]))
          timers.current.delete(alert.id)
        }, delay)
        timers.current.set(alert.id, t)
      }
    }
  }, [alerts, visible])

  // Clean up all timers on unmount
  useEffect(() => {
    const ref = timers.current
    return () => {
      for (const t of ref.values()) clearTimeout(t)
    }
  }, [])

  const dismiss = (id: string) => {
    const t = timers.current.get(id)
    if (t !== undefined) {
      clearTimeout(t)
      timers.current.delete(id)
    }
    setDismissed((prev) => new Set([...prev, id]))
  }

  if (visible.length === 0) return null

  return (
    <div className={styles.container} data-testid="alert-toast-container">
      {visible.map((alert) => (
        <AlertToastItem key={alert.id} alert={alert} onDismiss={() => dismiss(alert.id)} />
      ))}
    </div>
  )
}

// ── AlertToastItem ────────────────────────────────────────────────────────────

interface AlertToastItemProps {
  alert: AlertDto
  onDismiss: () => void
}

/** A single toast card. Renders emoji, message text, and a dismiss button. */
function AlertToastItem({ alert, onDismiss }: AlertToastItemProps) {
  const severityClass = {
    CRITICAL: styles.critical,
    WARNING: styles.warning,
    INFO: styles.info,
  }[alert.severity]

  return (
    <div
      className={`${styles.toast} ${severityClass}`}
      data-testid={`toast-${alert.id}`}
    >
      <span className={styles.emoji} aria-hidden="true">
        {SEVERITY_EMOJI[alert.severity]}
      </span>
      <span className={styles.message}>{alert.message}</span>
      <button
        className={styles.closeBtn}
        onClick={onDismiss}
        aria-label={`Dismiss: ${alert.message}`}
      >
        ×
      </button>
    </div>
  )
}
