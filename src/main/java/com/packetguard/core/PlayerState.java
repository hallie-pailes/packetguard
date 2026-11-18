package com.packetguard.core;

import com.packetguard.config.GuardConfig;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/** Per-player mutable state: rate-limit buckets and the strike counter. Thread-safe. */
public final class PlayerState {
    private final EnumMap<PacketKind, TokenBucket> buckets = new EnumMap<>(PacketKind.class);
    /** Only created for channels that have a configured policy, so an attacker cannot grow it. */
    private final Map<String, TokenBucket> channelBuckets = new HashMap<>();
    private int strikes;
    private int windowStart = Integer.MIN_VALUE;

    public synchronized boolean tryConsume(PacketKind kind, int tick, GuardConfig cfg) {
        TokenBucket bucket = buckets.get(kind);
        if (bucket == null) {
            GuardConfig.RateConfig rc = cfg.rateFor(kind);
            bucket = new TokenBucket(rc.capacity, rc.refillPerTick, tick);
            buckets.put(kind, bucket);
        }
        return bucket.tryConsume(tick);
    }

    public synchronized boolean tryConsumeChannel(String channel, int tick, double burst, double refillPerTick) {
        TokenBucket bucket = channelBuckets.get(channel);
        if (bucket == null) {
            bucket = new TokenBucket(burst, refillPerTick, tick);
            channelBuckets.put(channel, bucket);
        }
        return bucket.tryConsume(tick);
    }

    /** Adds a strike and returns the number of strikes inside the current window. */
    public synchronized int addStrike(int tick, int windowTicks) {
        if (windowStart == Integer.MIN_VALUE || tick - windowStart > windowTicks) {
            windowStart = tick;
            strikes = 0;
        }
        return ++strikes;
    }
}
