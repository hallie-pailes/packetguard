package net.minecraft.network.protocol.common.custom;
public interface CustomPacketPayload { record Type(Object id) {} Type type(); }
