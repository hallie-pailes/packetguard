package net.fabricmc.fabric.api.event.lifecycle.v1;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.server.MinecraftServer;
public class ServerLifecycleEvents { public interface Stopping { void onStopping(MinecraftServer s); } public static final Event<Stopping> SERVER_STOPPING = new Event<>(); }
