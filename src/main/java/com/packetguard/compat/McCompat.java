package com.packetguard.compat;

import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.level.ServerPlayer;

/**
 * THE ONLY PLACE that touches Minecraft packet accessors.
 *
 * <p>Minecraft internals change between versions (accessor names, record vs. class). If the
 * project fails to compile after a version bump, the errors should all be in this file.
 * Verify each accessor below against the decompiled 26.3 sources ({@code ./gradlew genSources}).
 */
public final class McCompat {
    private McCompat() {}

    public static PacketInfo containerClick(ServerboundContainerClickPacket p) {
        // VERIFY: accessor names (getContainerId / getSlotNum may be record-style in 26.3).
        return new PacketInfo(PacketKind.CONTAINER_CLICK,
                p.getContainerId(), p.getSlotNum(), null, null, -1);
    }

    public static PacketInfo creativeSlot(ServerboundSetCreativeModeSlotPacket p) {
        // VERIFY: slotNum() accessor.
        return new PacketInfo(PacketKind.CREATIVE_SLOT, -1, p.slotNum(), null, null, -1);
    }

    public static PacketInfo useItemOn(ServerboundUseItemOnPacket p) {
        // VERIFY: getHitResult().getBlockPos() and getSequence().
        BlockPos pos = p.getHitResult().getBlockPos();
        return new PacketInfo(PacketKind.BLOCK_USE, -1, PacketInfo.NO_SLOT, pos, null, p.getSequence());
    }

    /**
     * Only the block-breaking actions carry a meaningful position. Drop/swap/release actions
     * send BlockPos.ZERO, so range-checking them would reject legitimate packets.
     *
     * @return null if this action should not be validated
     */
    public static PacketInfo playerAction(ServerboundPlayerActionPacket p) {
        // VERIFY: getAction(), Action enum constants, getPos(), getSequence().
        ServerboundPlayerActionPacket.Action a = p.getAction();
        boolean blockAction = a == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK
                || a == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK
                || a == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK;
        if (!blockAction) return null;
        return new PacketInfo(PacketKind.BLOCK_ACTION, -1, PacketInfo.NO_SLOT, p.getPos(), null, p.getSequence());
    }

    public static PacketInfo customPayload(ServerboundCustomPayloadPacket p) {
        // VERIFY: payload().type().id() path.
        String channel = String.valueOf(p.payload().type().id());
        return new PacketInfo(PacketKind.CUSTOM_PAYLOAD, -1, PacketInfo.NO_SLOT, null, channel, -1);
    }

    // ---- Resync helpers: put the client back in sync after a blocked packet ----

    /** Re-send the open menu (and cursor item) so the client's prediction is rolled back. */
    public static void resyncMenu(ServerPlayer player) {
        player.containerMenu.sendAllDataToRemote();
        if (player.containerMenu != player.inventoryMenu) {
            player.inventoryMenu.sendAllDataToRemote();
        }
    }

    /** Acknowledge the block-change sequence and re-send the real block state. */
    public static void resyncBlock(ServerPlayer player, BlockPos pos, int sequence) {
        if (sequence >= 0) {
            player.connection.ackBlockChangesUpTo(sequence);
        }
        if (pos != null) {
            player.connection.send(new ClientboundBlockUpdatePacket(player.level(), pos));
        }
    }
}
