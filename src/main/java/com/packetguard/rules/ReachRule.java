package com.packetguard.rules;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Block interaction/breaking must be within the player's reach (plus tolerance). Uses the
 * player's live block-interaction-range attribute, so it follows game mode, attributes and
 * modded reach changes. A proxy sees only the client-reported coordinates in the packet; it
 * does not know where the server thinks the player is.
 */
public final class ReachRule implements Rule {
    @Override public String id() { return "reach"; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override public boolean appliesTo(PacketKind kind) {
        return kind == PacketKind.BLOCK_USE || kind == PacketKind.BLOCK_ACTION;
    }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        if (info.pos() == null) return Verdict.ALLOW;
        // Floor at 0: a negative limit would square to a positive one and let nearby blocks through.
        double limit = Math.max(0.0, player.blockInteractionRange() + ctx.config().reachTolerance);
        Vec3 eye = player.getEyePosition();
        double d2 = eye.distanceToSqr(Vec3.atCenterOf(info.pos()));
        if (d2 > limit * limit) {
            return Verdict.reject(id(), String.format(
                    "block %.1f away (limit %.1f incl. tolerance)", Math.sqrt(d2), limit));
        }
        return Verdict.ALLOW;
    }
}