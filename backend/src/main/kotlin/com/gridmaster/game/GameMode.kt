package com.gridmaster.game

/** The scenario mode a [GameSession] is running under. */
enum class GameMode {
    /** 8-mission guided curriculum on a small fictional network. */
    TUTORIAL,

    /** Open-ended campaign: grid grows from ~50 buses as regions unlock. */
    FREE_PLAY,

    /** Pre-loaded crisis scenario with a scored resolution target. */
    CHALLENGE,
}
