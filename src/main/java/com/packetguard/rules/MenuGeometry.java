package com.packetguard.rules;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Shared helper: how far is the player from the block that backs their open menu? */
public final class MenuGeometry {
    private MenuGeometry() {}

    /**
     * Distance in blocks from the player to the first block entity that backs a slot of the open
     * menu, or -1 if the menu has no single backing block entity (the player's own inventory,
     * double chests, entity-backed menus, many mod menus). Callers must treat -1 as "unknown",
     * not as "far away".
     */
    public static double distanceToBackingBlock(ServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu) return -1.0;
        for (Slot slot : menu.slots) {
            if (slot.container instanceof BlockEntity be) {
                double d2 = player.distanceToSqr(
                        be.getBlockPos().getX() + 0.5,
                        be.getBlockPos().getY() + 0.5,
                        be.getBlockPos().getZ() + 0.5);
                return Math.sqrt(d2);
            }
        }
        return -1.0;
    }
}
