package com.gridmaster.game

/** Clock run-state persisted with the session so it can resume in the same state. */
enum class ClockState {
    RUNNING,
    PAUSED,
    SLOW,
    STOPPED,
}
