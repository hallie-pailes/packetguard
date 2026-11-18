package com.packetguard.core;

import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters for the evaluation: packets evaluated/rejected per kind, rejections per rule, the
 * minimum {@link Visibility} needed to catch each rejected packet, and a log2 histogram of
 * validation latency in nanoseconds.
 */
public final class Metrics {
    private final LongAdder[] evaluated = adders(PacketKind.values().length);
    private final LongAdder[] rejected = adders(PacketKind.values().length);
    private final LongAdder[] minVisibility = adders(Visibility.values().length);
    private final Map<String, LongAdder> rejectedByRule = new ConcurrentHashMap<>();
    private final AtomicLongArray latencyLog2 = new AtomicLongArray(64);

    private static LongAdder[] adders(int n) {
        LongAdder[] a = new LongAdder[n];
        for (int i = 0; i < n; i++) a[i] = new LongAdder();
        return a;
    }

    /**
     * @param countPacket whether this call should add to the "evaluated" count. Custom payloads
     *                    are evaluated in two phases, and must be counted once per packet.
     */
    void record(PacketKind kind, long nanos, Verdict verdict, boolean countPacket) {
        if (countPacket) evaluated[kind.ordinal()].increment();
        latencyLog2.incrementAndGet(63 - Long.numberOfLeadingZeros(Math.max(1L, nanos)));
        if (!verdict.allowed()) {
            rejected[kind.ordinal()].increment();
            rejectedByRule.computeIfAbsent(verdict.ruleId(), k -> new LongAdder()).increment();
        }
    }

    /** Called once per rejected packet with the lowest visibility among the rules that rejected it. */
    void recordMinVisibility(Visibility v) {
        minVisibility[v.ordinal()].increment();
    }

    public long evaluatedCount(PacketKind k) { return evaluated[k.ordinal()].sum(); }

    public long rejectedCount(PacketKind k) { return rejected[k.ordinal()].sum(); }

    public long minVisibilityCount(Visibility v) { return minVisibility[v.ordinal()].sum(); }

    /** Approximate latency percentile in nanoseconds (upper edge of the log2 bucket). */
    public long latencyPercentileNanos(double p) {
        long total = 0;
        for (int i = 0; i < 64; i++) total += latencyLog2.get(i);
        if (total == 0) return 0;
        long target = (long) Math.ceil(total * p);
        long seen = 0;
        for (int i = 0; i < 64; i++) {
            seen += latencyLog2.get(i);
            if (seen >= target) return 1L << Math.min(62, i + 1);
        }
        return 0;
    }

    public void flush(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            try (Writer w = Files.newBufferedWriter(file)) {
                w.write("section,key,value\n");
                for (PacketKind k : PacketKind.values()) {
                    w.write("evaluated," + k + "," + evaluated[k.ordinal()].sum() + "\n");
                    w.write("rejected," + k + "," + rejected[k.ordinal()].sum() + "\n");
                }
                for (Map.Entry<String, LongAdder> e : rejectedByRule.entrySet()) {
                    w.write("rejected_by_rule," + e.getKey() + "," + e.getValue().sum() + "\n");
                }
                for (Visibility v : Visibility.values()) {
                    w.write("min_visibility_of_rejections," + v + "," + minVisibility[v.ordinal()].sum() + "\n");
                }
                w.write("latency_ns,p50," + latencyPercentileNanos(0.50) + "\n");
                w.write("latency_ns,p99," + latencyPercentileNanos(0.99) + "\n");
                for (int i = 0; i < 64; i++) {
                    long c = latencyLog2.get(i);
                    if (c > 0) w.write("latency_bucket_log2_ns," + i + "," + c + "\n");
                }
            }
        } catch (IOException e) {
            // Metrics are best-effort; never let them break the server.
        }
    }
}
