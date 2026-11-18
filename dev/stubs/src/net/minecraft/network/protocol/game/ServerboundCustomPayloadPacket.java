package net.minecraft.network.protocol.game;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
public class ServerboundCustomPayloadPacket { public String channel; public CustomPacketPayload payload(){ return () -> new CustomPacketPayload.Type(channel); } }
