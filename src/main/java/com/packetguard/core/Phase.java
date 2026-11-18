package com.packetguard.core;

/**
 * Which subset of the rules a call to {@link RuleEngine#allow} evaluates.
 *
 * <p>Most packets are validated on the main server thread with {@link #ALL}. Custom payloads
 * arrive on the network thread, where game state must not be read, so they are validated in two
 * phases: {@link #THREAD_SAFE} on the network thread (cheap, drops floods before they are
 * queued), then {@link #STATE_ONLY} after the packet has been re-queued onto the main thread.
 */
public enum Phase {
    ALL, THREAD_SAFE, STATE_ONLY;

    public boolean includes(Visibility v) {
        return switch (this) {
            case ALL -> true;
            case THREAD_SAFE -> v != Visibility.GAME_STATE;
            case STATE_ONLY -> v == Visibility.GAME_STATE;
        };
    }
}
