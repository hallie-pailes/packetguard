package com.packetguard.core;

import com.packetguard.config.GuardConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * One validation predicate. Every rule declares its {@link Visibility}, which is how the
 * evaluation separates what a network proxy could do from what needs the game engine.
 */
public interface Rule {

    /** Everything a rule may need besides the packet and the player. */
    record Ctx(int tick, MinecraftServer server, GuardConfig config, PlayerState state) {}

    /** Stable id, used in config ({@code disabledRules}), logs and metrics. */
    String id();

    /** The least a decision-maker must see to implement this rule. */
    Visibility visibility();

    boolean appliesTo(PacketKind kind);

    /**
     * Rules at {@link Visibility#GAME_STATE} are only called on the main server thread.
     * Rules at lower visibility must be thread-safe: they can run on the network thread.
     */
    Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx);
}
