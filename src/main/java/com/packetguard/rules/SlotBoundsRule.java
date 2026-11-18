package com.packetguard.rules;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * Slot index must exist in the open menu. Valid values are the same ones vanilla accepts:
 * -1, -999 (click outside the window), or 0..slotCount-1.
 *
 * <p>A proxy can only apply a fixed global bound (see {@code StructuralRule}); the real limit
 * depends on which menu, including mod menus, is open, which only the engine knows.
 */
public final class SlotBoundsRule implements Rule {
    private static final int OUTSIDE = -999;

    @Override public String id() { return "slot_bounds"; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override public boolean appliesTo(PacketKind kind) { return kind == PacketKind.CONTAINER_CLICK; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        // If the menu id mismatches, the context rule already handled it; do not double-judge.
        if (player.containerMenu.containerId != info.containerId()) return Verdict.ALLOW;
        int slot = info.slot();
        int count = player.containerMenu.slots.size();
        if (slot == -1 || slot == OUTSIDE || (slot >= 0 && slot < count)) return Verdict.ALLOW;
        return Verdict.reject(id(), "slot " + slot + " not valid for menu with " + count + " slots");
    }
}
