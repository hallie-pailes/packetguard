/*  The settings, loaded from config.json with Gson. */
package com.packetguard.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Visibility;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JSON-backed configuration. A default file is written on first start.
 *
 * <p>This file is also read by the network-proxy baseline ({@code baseline/}), which uses the
 * {@code rates} and {@code structural} sections, so both systems apply identical thresholds.
 */
public final class GuardConfig {
    private static final Logger LOG = LoggerFactory.getLogger("packetguard");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public enum Mode {
        /** Evaluate and log, but never block anything. Use first, to tune thresholds on real traffic. */
        MONITOR,
        /** Block rejected packets, resync the client, count strikes. */
        ENFORCE
    }

    public static final class RateConfig {
        public double capacity;
        public double refillPerTick;

        public RateConfig() {}

        public RateConfig(double capacity, double refillPerTick) {
            this.capacity = capacity;
            this.refillPerTick = refillPerTick;
        }
    }

    /** Bounds a network proxy can also check (PACKET visibility). */
    public static final class Structural {
        public int minSlot = -999;
        public int maxSlot = 4096;
        public boolean requireNonNegativeContainerId = true;
        /**
         * Largest serverbound packet accepted. Used by the proxy baseline only: by the time the
         * mod sees a packet the engine has already decoded it, so size is not observable there.
         */
        public int maxFrameBytes = 32768;
    }

    /**
     * Policy for one mod-defined custom payload channel (keyed by channel id, e.g. "mymod:transfer").
     * Fills the gap that vanilla and generic tools leave: mods register handlers that nobody checks.
     */
    public static final class PayloadPolicy {
        /** GAME_STATE: reject if the sender is dead. */
        public boolean requireAlive = false;
        /** GAME_STATE: reject if the sender is a spectator. */
        public boolean forbidSpectator = false;
        /** GAME_STATE: reject (softly, no strike) unless a non-inventory menu is open. */
        public boolean requireOpenMenu = false;
        /** GAME_STATE: reject if the open menu's backing block is farther than this many blocks (0 = off). */
        public double maxMenuDistance = 0;
        /** CONNECTION: sustained packets per second allowed on this channel (0 = no per-channel limit). */
        public double maxPerSecond = 0;
        /** CONNECTION: burst size for the per-channel limit (0 = same as maxPerSecond). */
        public double burst = 0;
    }

    public Mode mode = Mode.MONITOR;

    /**
     * Highest visibility level the engine may use when deciding. {@code PACKET} = structural rules
     * only; {@code CONNECTION} = adds rate limits (a proxy-equivalent rule set); {@code GAME_STATE}
     * = full system. Used for the ablation.
     */
    public Visibility visibilityCeiling = Visibility.GAME_STATE;

    /**
     * Experiment switch. When true, every rule is evaluated regardless of the ceiling, and each
     * event records which rules would have rejected and the minimum visibility that was needed.
     * Combined with MONITOR mode, one run measures how many bad packets a proxy-level rule set
     * could never have caught.
     */
    public boolean shadowEvaluation = false;

    /** Rule ids to switch off individually (ablation studies). */
    public Set<String> disabledRules = new HashSet<>();

    public Structural structural = new Structural();

    /** Extra blocks of slack added to reach checks to absorb latency and movement. */
    public double reachTolerance = 1.5;

    /** Stricter-than-vanilla cap on distance to a block-entity-backed container (blocks). */
    public double maxContainerDistance = 8.0;

    public int strikeLimit = 20;
    public int strikeWindowTicks = 1200;
    public boolean kickOnStrikeLimit = true;

    public boolean logEvents = true;
    public int metricsFlushIntervalTicks = 6000;

    /** Keyed by PacketKind name. These numbers are placeholders: calibrate them in MONITOR mode. */
    public Map<String, RateConfig> rates = defaultRates();

    /** Keyed by channel id. Empty by default; see README for an example. */
    public Map<String, PayloadPolicy> payloadPolicies = new LinkedHashMap<>();

    private static Map<String, RateConfig> defaultRates() {
        Map<String, RateConfig> m = new LinkedHashMap<>();
        m.put(PacketKind.CONTAINER_CLICK.name(), new RateConfig(120, 20));
        m.put(PacketKind.CREATIVE_SLOT.name(), new RateConfig(60, 10));
        m.put(PacketKind.BLOCK_USE.name(), new RateConfig(30, 4));
        m.put(PacketKind.BLOCK_ACTION.name(), new RateConfig(60, 8));
        m.put(PacketKind.CUSTOM_PAYLOAD.name(), new RateConfig(100, 10));
        return m;
    }

    public RateConfig rateFor(PacketKind kind) {
        RateConfig rc = rates.get(kind.name());
        return rc != null ? rc : new RateConfig(100, 10);
    }

    private void normalize() {
        if (rates == null) rates = defaultRates();
        if (disabledRules == null) disabledRules = new HashSet<>();
        if (structural == null) structural = new Structural();
        if (payloadPolicies == null) payloadPolicies = new LinkedHashMap<>();
        if (visibilityCeiling == null) visibilityCeiling = Visibility.GAME_STATE;
        if (mode == null) mode = Mode.MONITOR;
    }

    /**
     * Parses an existing config file, or returns null (and logs) if it cannot be read. Used for
     * hot reload, where a typo must keep the previous working configuration instead of silently
     * falling back to defaults.
     */
    public static GuardConfig tryLoad(Path file) {
        try (Reader r = Files.newBufferedReader(file)) {
            GuardConfig cfg = GSON.fromJson(r, GuardConfig.class);
            if (cfg == null) return null;
            cfg.normalize();
            return cfg;
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not parse {}; keeping the previous configuration.", file, e);
            return null;
        }
    }

    public static GuardConfig loadOrCreate(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file)) {
                    GuardConfig cfg = GSON.fromJson(r, GuardConfig.class);
                    if (cfg != null) {
                        cfg.normalize();
                        return cfg;
                    }
                }
            }
            GuardConfig cfg = new GuardConfig();
            try (Writer w = Files.newBufferedWriter(file)) {
                GSON.toJson(cfg, w);
            }
            return cfg;
        } catch (IOException | RuntimeException e) {
            LOG.error("Could not read {}; falling back to defaults (MONITOR mode).", file, e);
            return new GuardConfig();
        }
    }
}
