package com.packetguard;

import com.packetguard.config.GuardConfig;
import com.packetguard.core.RuleEngine;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

public final class PacketGuardMod implements ModInitializer {
    /** How often (in ticks) the config file is checked for edits. 20 ticks = 1 second. */
    private static final int CONFIG_POLL_TICKS = 20;

    @Override
    public void onInitialize() {
        // VERIFY: Fabric API event class/method names for 26.3 (some were renamed in 26.1).
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("packetguard");
        GuardConfig config = GuardConfig.loadOrCreate(dir.resolve("config.json"));
        RuleEngine engine = new RuleEngine(config, dir);
        PacketGuard.install(engine);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                engine.forget(handler.player.getUUID()));

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            int tick = server.getTickCount();
            if (tick % CONFIG_POLL_TICKS == 0) {
                engine.reloadIfChanged();
            }
            int interval = engine.config().metricsFlushIntervalTicks;
            if (interval > 0 && tick % interval == 0) {
                engine.flushMetrics();
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> engine.close());
    }
}
