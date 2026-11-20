/* Runs the rules, applies the mode, logs, counts strikes, reloads config. */
package com.packetguard.core;

import com.packetguard.config.GuardConfig;
import com.packetguard.rules.ContainerContextRule;
import com.packetguard.rules.ContainerRangeRule;
import com.packetguard.rules.CreativeModeRule;
import com.packetguard.rules.PayloadPolicyRule;
import com.packetguard.rules.RateLimitRule;
import com.packetguard.rules.ReachRule;
import com.packetguard.rules.SlotBoundsRule;
import com.packetguard.rules.StructuralRule;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs the staged validation pipeline and applies the configured action.
 *
 * <p>Rules run cheapest-first. Which rules are <em>enforced</em> is set by
 * {@code visibilityCeiling}. With {@code shadowEvaluation} on, rules above the ceiling still run
 * and are recorded, but never decide: this measures what a lower-visibility defense would miss.
 *
 * <p>The configuration can be edited while the server runs ({@link #reloadIfChanged()}); each
 * packet is judged against one consistent snapshot of it.
 */
public final class RuleEngine implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger("packetguard");

    /** A rule's rejection together with the visibility that rule needs. */
    private record Rejection(Verdict verdict, Visibility visibility) {}

    private static final class Outcome {
        /** First rejection from a rule within the ceiling: the one that decides. */
        Rejection enforced;
        /** Every rejection observed (more than one only in shadow mode). */
        final List<Rejection> all = new ArrayList<>(2);
    }

    private volatile GuardConfig config;
    private final Path configFile;
    private volatile long configStamp;
    private final Metrics metrics = new Metrics();
    private final EventLog eventLog;
    private final Path metricsFile;
    private final List<Rule> rules;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    public RuleEngine(GuardConfig config, Path dir) {
        this.config = config;
        this.configFile = dir.resolve("config.json");
        this.configStamp = stampOf(configFile);
        this.eventLog = new EventLog(dir.resolve("events.jsonl"), config.logEvents);
        this.metricsFile = dir.resolve("metrics.csv");
        this.rules = List.of(
                new StructuralRule(),        // PACKET
                new RateLimitRule(),         // CONNECTION
                new ContainerContextRule(),  // GAME_STATE
                new SlotBoundsRule(),        // GAME_STATE
                new CreativeModeRule(),      // GAME_STATE
                new PayloadPolicyRule(),     // GAME_STATE (custom payloads)
                new ContainerRangeRule(),    // GAME_STATE
                new ReachRule());            // GAME_STATE
        LOG.info("PacketGuard loaded: mode={}, ceiling={}, shadow={}, disabled={}",
                config.mode, config.visibilityCeiling, config.shadowEvaluation, config.disabledRules);
    }

    /** The configuration currently in force. */
    public GuardConfig config() { return config; }

    public Metrics metrics() { return metrics; }

    public void flushMetrics() { metrics.flush(metricsFile); }

    public void forget(UUID player) { states.remove(player); }

    @Override
    public void close() {
        flushMetrics();
        eventLog.close();
    }

    private static long stampOf(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Re-reads the config file if it was edited since the last check. A file that fails to parse
     * keeps the previous configuration and is not retried until it is edited again. Existing rate
     * buckets keep the capacity they were created with; new buckets use the new values. Whether
     * events are logged ({@code logEvents}) is fixed at startup.
     */
    public void reloadIfChanged() {
        long stamp = stampOf(configFile);
        if (stamp == configStamp) return;
        configStamp = stamp;
        if (stamp == 0L) return; // file removed: keep running with what we have
        GuardConfig fresh = GuardConfig.tryLoad(configFile);
        if (fresh == null) return;
        config = fresh;
        LOG.info("PacketGuard config reloaded: mode={}, ceiling={}, shadow={}, disabled={}",
                fresh.mode, fresh.visibilityCeiling, fresh.shadowEvaluation, fresh.disabledRules);
        // Mark the change in the event log so an analysis can split runs by configuration.
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timeMillis", System.currentTimeMillis());
        row.put("event", "config_reload");
        row.put("mode", fresh.mode.name());
        row.put("ceiling", fresh.visibilityCeiling.name());
        row.put("shadow", fresh.shadowEvaluation);
        row.put("disabledRules", new ArrayList<>(fresh.disabledRules));
        eventLog.write(row);
    }

    /** Validates a packet on the main thread with every rule ({@link Phase#ALL}). */
    public boolean allow(ServerPlayer player, PacketInfo info, Runnable onReject) {
        return allow(player, info, onReject, Phase.ALL);
    }

    /**
     * Validates a packet.
     *
     * @param onReject called (on the calling thread) when a packet is blocked in ENFORCE mode,
     *                 to resync the client; may be null
     * @param phase    which rules to run (see {@link Phase})
     * @return true if the packet should proceed to vanilla/mod handling
     */
    public boolean allow(ServerPlayer player, PacketInfo info, Runnable onReject, Phase phase) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return true;
        final GuardConfig cfg = this.config; // one consistent snapshot for this packet
        int tick = server.getTickCount();
        PlayerState state = states.computeIfAbsent(player.getUUID(), id -> new PlayerState());
        Rule.Ctx ctx = new Rule.Ctx(tick, server, cfg, state);

        long t0 = System.nanoTime();
        Outcome out = evaluate(cfg, player, info, ctx, phase);
        long nanos = System.nanoTime() - t0;

        Verdict decisive = out.enforced == null ? Verdict.ALLOW : out.enforced.verdict();
        boolean enforce = cfg.mode == GuardConfig.Mode.ENFORCE;
        boolean blocked = enforce && out.enforced != null;

        // The network-thread pass over a custom payload must not count the packet twice.
        boolean countPacket = phase != Phase.THREAD_SAFE || blocked;
        metrics.record(info.kind(), nanos, decisive, countPacket);

        if (out.all.isEmpty()) return true;

        Visibility minVis = out.all.get(0).visibility();
        List<String> ids = new ArrayList<>(out.all.size());
        for (Rejection r : out.all) {
            ids.add(r.verdict().ruleId());
            if (r.visibility().ordinal() < minVis.ordinal()) minVis = r.visibility();
        }
        metrics.recordMinVisibility(minVis);

        Rejection primary = out.enforced != null ? out.enforced : out.all.get(0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("timeMillis", System.currentTimeMillis());
        row.put("tick", tick);
        row.put("player", player.getName().getString());
        row.put("uuid", player.getUUID().toString());
        row.put("kind", info.kind().name());
        row.put("rule", primary.verdict().ruleId());
        row.put("reason", primary.verdict().reason());
        row.put("rejectedBy", ids);
        row.put("minVisibility", minVis.name());
        row.put("containerId", info.containerId());
        row.put("slot", info.slot() == PacketInfo.NO_SLOT ? null : info.slot());
        row.put("channel", info.channel());
        row.put("phase", phase.name());
        row.put("mode", cfg.mode.name());
        row.put("ceiling", cfg.visibilityCeiling.name());
        row.put("blocked", blocked);
        eventLog.write(row);

        if (!blocked) return true;

        if (onReject != null) onReject.run();
        if (decisive.strike()) applyStrike(cfg, server, player, state, tick);
        return false;
    }

    private Outcome evaluate(GuardConfig cfg, ServerPlayer player, PacketInfo info, Rule.Ctx ctx, Phase phase) {
        Outcome out = new Outcome();
        boolean shadow = cfg.shadowEvaluation;
        Visibility ceiling = cfg.visibilityCeiling;
        for (Rule rule : rules) {
            if (!rule.appliesTo(info.kind())) continue;
            if (!phase.includes(rule.visibility())) continue;
            if (cfg.disabledRules.contains(rule.id())) continue;
            boolean inCeiling = rule.visibility().ordinal() <= ceiling.ordinal();
            if (!inCeiling && !shadow) continue;
            Verdict v = rule.check(player, info, ctx);
            if (v.allowed()) continue;
            Rejection r = new Rejection(v, rule.visibility());
            out.all.add(r);
            if (inCeiling && out.enforced == null) {
                out.enforced = r;
                if (!shadow) break;
            }
        }
        return out;
    }

    private void applyStrike(GuardConfig cfg, MinecraftServer server, ServerPlayer player,
                             PlayerState state, int tick) {
        int strikes = state.addStrike(tick, cfg.strikeWindowTicks);
        if (cfg.kickOnStrikeLimit && strikes >= cfg.strikeLimit) {
            // May be called from the network thread (custom payloads): hop to the main thread.
            server.execute(() -> player.connection.disconnect(
                    Component.literal("Disconnected: too many invalid packets")));
        }
    }
}
