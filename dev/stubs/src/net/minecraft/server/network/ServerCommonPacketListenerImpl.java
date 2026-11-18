package net.minecraft.server.network;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
public abstract class ServerCommonPacketListenerImpl { public int customPayloadsHandled; public void handleCustomPayload(ServerboundCustomPayloadPacket p){customPayloadsHandled++;} }
