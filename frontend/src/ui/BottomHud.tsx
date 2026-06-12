import { useGameStore } from '../state/useGameStore'
import { useShallow } from 'zustand/react/shallow'
import { SPEED_STEPS } from './hud'
import type { SpeedStep } from './hud'
import styles from './BottomHud.module.css'

interface BottomHudProps {
  /** Called when the player clicks "Run Dispatch" — opens the DispatchPanel. */
  onOpenDispatch?: () => void
  /** Called when the player clicks "Plan Day" — opens the PlanningPanel. */
  onOpenPlanning?: () => void
}

/**
 * Bottom HUD — clock controls (left) and contextual action buttons (right).
 *
 * Clock controls:
 * - Play/pause toggle: dispatches PauseClock or ResumeClock command
 * - Speed selector (1×, 10×, 60×, 100×): dispatches SetClockSpeed command
 *
 * Contextual action buttons:
 * - "Run Dispatch" — opens DispatchPanel (#88)
 * - "Plan Day" — always shown (opens planning panel #89 when implemented)
 * - Event button — shown when pendingEventCards.length > 0 (first card)
 * - Overflow "…" button — shown when > 4 actions present (max 4 visible)
 *
 * All buttons are disabled when no session is active.
 *
 * @see docs/engineering/13-hud.md
 */
export function BottomHud({ onOpenDispatch, onOpenPlanning }: BottomHudProps) {
  const { clockState, clockSpeedMultiplier, sessionId, network, pendingEventCards, sendCommandOptimistic } =
    useGameStore(useShallow((s) => ({
      clockState: s.clockState,
      clockSpeedMultiplier: s.clockSpeedMultiplier,
      sessionId: s.sessionId,
      network: s.network,
      pendingEventCards: s.pendingEventCards,
      sendCommandOptimistic: s.sendCommandOptimistic,
    })))

  const disabled = !sessionId
  const networkReady = network !== null
  const isRunning = clockState === 'RUNNING'

  function handlePlayPause() {
    sendCommandOptimistic({
      commandType: isRunning ? 'PauseClock' : 'ResumeClock',
      payload: {},
    })
  }

  function handleSpeed(multiplier: SpeedStep) {
    if (multiplier === clockSpeedMultiplier) return
    sendCommandOptimistic({
      commandType: 'SetClockSpeed',
      payload: { multiplier },
    })
  }

  function handleDispatch() {
    onOpenDispatch?.()
  }

  const firstEvent = pendingEventCards[0]

  return (
    <div className={styles.root} data-testid="bottom-hud">
      {/* ── Clock controls ── */}
      <div className={styles.clockSection}>
        <button
          className={styles.playPause}
          onClick={handlePlayPause}
          disabled={disabled}
          aria-label={isRunning ? 'Pause' : 'Play'}
          data-testid="hud-playpause-btn"
        >
          {isRunning ? '⏸' : '▶'}
        </button>

        <div className={styles.speedGroup} role="group" aria-label="Clock speed">
          {SPEED_STEPS.map((step) => (
            <button
              key={step}
              className={`${styles.speedBtn} ${clockSpeedMultiplier === step ? styles.speedActive : ''}`}
              onClick={() => handleSpeed(step)}
              disabled={disabled}
              aria-pressed={clockSpeedMultiplier === step}
              data-testid={`btn-speed-${step}`}
            >
              {step}×
            </button>
          ))}
        </div>
      </div>

      {/* ── Contextual actions ── */}
      <div className={styles.actionSection} data-testid="action-section">
        <button
          className={styles.actionBtn}
          onClick={handleDispatch}
          disabled={disabled || !networkReady}
          data-testid="btn-dispatch"
        >
          Run Dispatch
        </button>

        <button
          className={styles.actionBtn}
          onClick={() => onOpenPlanning?.()}
          disabled={disabled}
          data-testid="btn-plan-day"
        >
          Plan Day
        </button>

        {firstEvent && (
          <button
            className={`${styles.actionBtn} ${styles.actionEvent}`}
            disabled={disabled}
            data-testid="btn-event"
            // TODO: open event card panel (#87)
          >
            {firstEvent.title}
          </button>
        )}
      </div>
    </div>
  )
}

