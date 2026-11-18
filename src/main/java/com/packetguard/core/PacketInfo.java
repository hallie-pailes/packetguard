package com.packetguard.core;

import net.minecraft.core.BlockPos;

/**
 * Version-independent view of a decoded packet. Rules only ever see this record,
 * so all Minecraft packet-class accessors live in one place ({@code McCompat}).
 *
 * @param kind        packet category
 * @param containerId menu id the client claims to be clicking in, or -1 if not applicable
 * @param slot        slot index, or Integer.MIN_VALUE if not applicable
 * @param pos         targeted block position, or null if not applicable
 * @param channel     custom payload channel id, or null if not applicable
 * @param sequence    block-change acknowledgement sequence, or -1 if not applicable
 */
public record PacketInfo(PacketKind kind, int containerId, int slot, BlockPos pos, String channel, int sequence) {
    public static final int NO_SLOT = Integer.MIN_VALUE;
}
