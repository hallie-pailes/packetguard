package com.packetguard.core;

/**
 * What a rule needs to be able to see in order to decide. This is the core of the evaluation:
 * a network proxy can only implement rules at {@link #PACKET} and {@link #CONNECTION}; rules at
 * {@link #GAME_STATE} are impossible outside the game process.
 *
 * <p>The order matters: a configured <em>ceiling</em> admits every rule whose visibility is
 * less than or equal to it.
 */
public enum Visibility {
    /** Decidable from the bytes of this one packet (field ranges, sizes). */
    PACKET,
    /** Needs per-connection history only (rates, counters, sequence numbers). */
    CONNECTION,
    /** Needs live game state: open menu, player position, game mode, world contents. */
    GAME_STATE
}
