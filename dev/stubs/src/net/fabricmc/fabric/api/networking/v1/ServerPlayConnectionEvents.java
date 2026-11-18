package net.fabricmc.fabric.api.networking.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
public class ServerPlayConnectionEvents { public interface Disconnect { void onPlayDisconnect(ServerGamePacketListenerImpl h, MinecraftServer s); } public static final Event<Disconnect> DISCONNECT = new Event<>(); }
