package com.gridmaster.game

/** Persisted run-state of the game clock. Restored on session resume. */
enum class ClockState {
    /** Clock is advancing at the configured [GameSession.clockSpeedMultiplier]. */
    RUNNING,

    /** Clock is halted; physics state is frozen. */
    PAUSED,

    /** Clock is advancing at a reduced multiplier (auto-slow on events). */
    SLOW,

    /** Clock is permanently halted (session completed or failed). */
    STOPPED,
}
