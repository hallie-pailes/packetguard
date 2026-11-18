package net.fabricmc.fabric.api.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;
public class ServerTickEvents { public interface EndTick { void onEndTick(MinecraftServer s); } public static final Event<EndTick> END_SERVER_TICK = new Event<>(); }
