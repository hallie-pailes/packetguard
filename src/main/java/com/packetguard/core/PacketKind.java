package com.packetguard.core;

/** The categories of client-to-server packets PacketGuard validates. */
public enum PacketKind {
    /** Slot click inside an open menu (inventory, chest, mod menus). */
    CONTAINER_CLICK,
    /** Creative-mode "set slot" request. */
    CREATIVE_SLOT,
    /** Use item on / place against a block. */
    BLOCK_USE,
    /** Start/stop/abort breaking a block. */
    BLOCK_ACTION,
    /** Mod-defined custom payload. */
    CUSTOM_PAYLOAD
}
