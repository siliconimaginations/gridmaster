import { useMemo, useRef, useEffect } from 'react'
import { useShallow } from 'zustand/react/shallow'
import { useGameStore } from '../state/useGameStore'
import styles from './TimelineStrip.module.css'

/** Width of each hour column in pixels. */
const HOUR_WIDTH_PX = 56
const HOURS = Array.from({ length: 24 }, (_, i) => i)

/**
 * Day-ahead timeline strip — a 24-hour scrollable strip above the bottom HUD.
 *
 * Displays:
 * - A "Now ▼" indicator at the current game-time hour, auto-scrolled to centre.
 * - Commitment blocks: green if the hour is covered by the active UC schedule,
 *   grey hatched otherwise (nudges player to commit generators).
 * - Even-hour labels (0h, 2h, … 22h) along the bottom edge.
 *
 * The UC schedule (`ucSchedule`) is a 24-element boolean array written to the
 * store when the player confirms a schedule in the DispatchPanel. When null,
 * all blocks render as "uncommitted" (placeholder state).
 *
 * @see docs/ux/04-time-axis.md
 * @see issue #84
 */
export function TimelineStrip() {
  const { gameTimeMinutes, ucSchedule } = useGameStore(
    useShallow((s) => ({ gameTimeMinutes: s.gameTimeMinutes, ucSchedule: s.ucSchedule })),
  )

  const scrollRef = useRef<HTMLDivElement>(null)

  /** Current hour within the simulated day (0–23). */
  const currentHour = useMemo(
    () => Math.floor((gameTimeMinutes % 1440) / 60),
    [gameTimeMinutes],
  )

  // Keep the "Now" indicator centred in the visible strip.
  useEffect(() => {
    const el = scrollRef.current
    if (!el) return
    const targetScroll = currentHour * HOUR_WIDTH_PX - el.clientWidth / 2 + HOUR_WIDTH_PX / 2
    el.scrollLeft = Math.max(0, targetScroll)
  }, [currentHour])

  return (
    <div className={styles.root} data-testid="timeline-strip">
      <div className={styles.scroll} ref={scrollRef}>
        <div
          className={styles.track}
          style={{ width: HOURS.length * HOUR_WIDTH_PX }}
        >
          {/* "Now" indicator */}
          <div
            className={styles.nowIndicator}
            style={{ left: currentHour * HOUR_WIDTH_PX + HOUR_WIDTH_PX / 2 }}
            data-testid="timeline-now"
          >
            ▼
          </div>

          {/* Commitment blocks */}
          <div className={styles.blocks}>
            {HOURS.map((h) => {
              const committed = ucSchedule ? ucSchedule[h] : false
              return (
                <div
                  key={h}
                  className={`${styles.block} ${committed ? styles.committed : styles.uncommitted}`}
                  style={{ width: HOUR_WIDTH_PX - 2 }}
                  data-testid={`timeline-block-${h}`}
                  title={`${h}:00 — ${committed ? 'Committed' : 'Not scheduled'}`}
                />
              )
            })}
          </div>

          {/* Hour labels */}
          <div className={styles.labels}>
            {HOURS.map((h) => (
              <div
                key={h}
                className={`${styles.label} ${h === currentHour ? styles.labelNow : ''}`}
                style={{ width: HOUR_WIDTH_PX }}
              >
                {h % 2 === 0 ? `${h}h` : ''}
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}
