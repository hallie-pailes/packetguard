package com.packetguard.rules;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * The open menu must still be usable by the player <em>at the moment of this packet</em>, and
 * (optionally) a block-entity-backed container must be within a configured distance.
 *
 * <p>Vanilla re-checks {@code stillValid} once per tick, which leaves a window of up to one
 * tick between a state change (teleport, block removed) and the next check. Checking per packet
 * closes that window. {@code stillValid} also covers mod menus that implement it correctly.
 *
 * <p>Limitation: double chests and some mod containers do not expose a single block entity (see MenuGeometry),
 * so the extra distance cap is skipped for them and only {@code stillValid} applies.
 */
public final class ContainerRangeRule implements Rule {
    @Override public String id() { return "container_range"; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override public boolean appliesTo(PacketKind kind) { return kind == PacketKind.CONTAINER_CLICK; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu.containerId != info.containerId()) return Verdict.ALLOW; // handled by context rule
        if (menu == player.inventoryMenu) return Verdict.ALLOW;           // always usable

        if (!menu.stillValid(player)) {
            return Verdict.reject(id(), "open menu is no longer valid for this player");
        }

        double max = ctx.config().maxContainerDistance;
        double d = MenuGeometry.distanceToBackingBlock(player); // -1 = unknown, not "far"
        if (d >= 0 && d > max) {
            return Verdict.reject(id(), String.format(
                    "container %.1f blocks away (limit %.1f)", d, max));
        }
        return Verdict.ALLOW;
    }
}
