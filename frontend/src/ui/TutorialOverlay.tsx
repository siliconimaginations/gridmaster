import { useGameStore } from '../state/useGameStore'
import styles from './TutorialOverlay.module.css'

// ── Step content ──────────────────────────────────────────────────────────────

interface StepContent {
  title: string
  instruction: string
  hint: string
}

const STEPS: Record<number, StepContent> = {
  1: {
    title: 'Step 1 of 5 — Observe the grid',
    instruction: 'The simulation is running. Watch the health score in the top-right corner and the bus colours on the map.',
    hint: 'Green buses are healthy. Red or yellow means voltage violations.',
  },
  2: {
    title: 'Step 2 of 5 — Adjust generator dispatch',
    instruction: 'Open the Dispatch panel (bottom toolbar) and change the output of any generator.',
    hint: 'Click "Dispatch" in the bottom bar, then drag a generator slider.',
  },
  3: {
    title: 'Step 3 of 5 — Handle a demand spike',
    instruction: 'A demand spike just hit the grid! Increase generator output to restore balance before health drops too low.',
    hint: 'Open the Dispatch panel and raise committed generators to cover the extra load.',
  },
  4: {
    title: 'Step 4 of 5 — Pause, inspect, and resume',
    instruction: 'Press Pause to freeze the simulation, inspect the grid state, then press Play to resume.',
    hint: 'The Pause button is in the bottom toolbar clock controls.',
  },
  5: {
    title: 'Tutorial complete!',
    instruction: "You've mastered the basics of grid operations. The simulation is now yours — keep the health score above 30 to stay in the game.",
    hint: 'Switch to Free Play mode from the session menu to explore without guidance.',
  },
}

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * Step-aware tutorial tooltip panel.
 *
 * Rendered only during TUTORIAL-mode sessions (when `tutorialStep` is non-null).
 * Anchored to the bottom-left of the HUD so it doesn't obscure the grid canvas
 * or the main bottom toolbar.
 *
 * Step content mirrors the `TutorialStep` enum descriptions on the backend.
 */
export function TutorialOverlay() {
  const step = useGameStore((s) => s.tutorialStep)

  if (step === null || step === undefined) return null

  const content = STEPS[step] ?? STEPS[1]
  const isComplete = step === 5

  return (
    <div
      className={`${styles.panel} ${isComplete ? styles.panelComplete : ''}`}
      data-testid="tutorial-overlay"
      data-step={step}
    >
      <div className={styles.badge}>
        {isComplete ? '✓' : `${step}/5`}
      </div>
      <div className={styles.body}>
        <div className={styles.title}>{content.title}</div>
        <div className={styles.instruction}>{content.instruction}</div>
        <div className={styles.hint}>{content.hint}</div>
      </div>
    </div>
  )
}
