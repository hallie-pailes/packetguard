package com.packetguard.rules;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * Creative "set slot" requests are only legal in creative mode. Needs the player's game mode,
 * which is server state a proxy never sees.
 *
 * <p>Vanilla checks this too; the rule exists for uniform logging and so behavior stays
 * correct if a mod or version change alters the vanilla handler.
 */
public final class CreativeModeRule implements Rule {
    @Override public String id() { return "creative_mode"; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override public boolean appliesTo(PacketKind kind) { return kind == PacketKind.CREATIVE_SLOT; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        if (player.isCreative()) return Verdict.ALLOW;
        return Verdict.reject(id(), "creative inventory action from a non-creative player");
    }
}
