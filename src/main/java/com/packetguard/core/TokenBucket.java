package com.packetguard.core;

/**
 * Tick-based token bucket (deterministic: uses the server tick counter, not wall-clock time).
 * Synchronized because custom payloads arrive on the network thread while other
 * packets are handled on the main thread.
 *
 * <p>The network-proxy baseline implements the same bucket with wall-clock time
 * (1 tick = 50 ms), since a proxy cannot see the server's tick counter.
 */
final class TokenBucket {
    private final double capacity;
    private final double refillPerTick;
    private double tokens;
    private int lastTick;

    TokenBucket(double capacity, double refillPerTick, int startTick) {
        this.capacity = capacity;
        this.refillPerTick = refillPerTick;
        this.tokens = capacity;
        this.lastTick = startTick;
    }

    synchronized boolean tryConsume(int tick) {
        int elapsed = tick - lastTick;
        if (elapsed > 0) {
            tokens = Math.min(capacity, tokens + elapsed * refillPerTick);
            lastTick = tick;
        }
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
