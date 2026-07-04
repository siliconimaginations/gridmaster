package com.gridmaster.game.tutorial

/**
 * Ordered steps of the guided tutorial session.
 *
 * Clients receive the current step number (1-based ordinal) via
 * [com.gridmaster.api.websocket.GameStateUpdate.tutorialStep].
 *
 * Transitions:
 *   1 OBSERVE        → after 3 ticks (automatic)
 *   2 DISPATCH       → after player sends SetGeneratorOutput command
 *   3 DEMAND_SPIKE   → after the scheduled demand-spike event fires
 *   4 PAUSE_RESUME   → after clock transitions RUNNING→PAUSED→RUNNING
 *   5 COMPLETE       → terminal; session marked completed
 */
enum class TutorialStep(val stepNumber: Int, val title: String, val instruction: String) {
    OBSERVE(
        stepNumber = 1,
        title = "Observe the grid",
        instruction = "The simulation is running. Watch the health score and load balance in the top bar.",
    ),
    DISPATCH(
        stepNumber = 2,
        title = "Adjust generator dispatch",
        instruction = "Open the Dispatch panel (⚡ button below) and change a generator's output.",
    ),
    DEMAND_SPIKE(
        stepNumber = 3,
        title = "Handle a demand spike",
        instruction = "A demand spike just hit the grid! Watch the alerts and adjust generation to compensate.",
    ),
    PAUSE_RESUME(
        stepNumber = 4,
        title = "Pause, inspect, and resume",
        instruction = "Press Pause to freeze the simulation, inspect the grid state, then press Play to resume.",
    ),
    COMPLETE(
        stepNumber = 5,
        title = "Tutorial complete!",
        instruction = "You've mastered the basics of grid operation. Switch to Free Play to run the real grid.",
    ),
}
