package com.packetguard.rules;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * Does this click belong to the menu the player actually has open right now?
 *
 * <p>Vanilla already silently ignores clicks whose container id does not match, so this rule
 * mostly adds logging and a uniform, measurable decision. It is a <em>soft</em> rejection (no
 * strike) because a click can legitimately arrive just after the server closed or replaced a menu.
 */
public final class ContainerContextRule implements Rule {
    @Override public String id() { return "container_context"; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override public boolean appliesTo(PacketKind kind) { return kind == PacketKind.CONTAINER_CLICK; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        if (player.isSpectator()) {
            return Verdict.reject(id(), "spectators cannot click slots");
        }
        int openId = player.containerMenu.containerId;
        if (openId != info.containerId()) {
            return Verdict.softReject(id(),
                    "click for menu " + info.containerId() + " but menu " + openId + " is open");
        }
        return Verdict.ALLOW;
    }
}
