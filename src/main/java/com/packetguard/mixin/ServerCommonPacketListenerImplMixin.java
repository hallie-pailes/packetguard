package com.packetguard.mixin;

import com.packetguard.PacketGuard;
import com.packetguard.compat.McCompat;
import com.packetguard.core.PacketInfo;
import com.packetguard.core.Phase;
import com.packetguard.core.RuleEngine;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Guard for mod-defined custom payloads, validated in two phases.
 *
 * <ol>
 *   <li><b>Network thread:</b> the cheap, thread-safe rules (rate budgets, per-channel budgets)
 *       run immediately, so a flood is dropped before it is queued. If the packet survives, it is
 *       re-queued onto the main server thread and this handler is called again.</li>
 *   <li><b>Main thread:</b> the game-state rules (payload context policy) run, where reading the
 *       open menu is safe and cannot race with the tick.</li>
 * </ol>
 *
 * <p>The low priority makes this hook run before Fabric API's own payload dispatch, so a
 * rejected payload never reaches other mods' receivers.
 */
@Mixin(value = ServerCommonPacketListenerImpl.class, priority = 500)
public abstract class ServerCommonPacketListenerImplMixin {

    @Inject(method = "handleCustomPayload", at = @At("HEAD"), cancellable = true)
    private void packetguard$customPayload(ServerboundCustomPayloadPacket packet, CallbackInfo ci) {
        RuleEngine engine = PacketGuard.engine();
        if (engine == null) return;
        // Only in-game connections have a player; ignore configuration-phase payloads.
        if (!((Object) this instanceof ServerGamePacketListenerImpl play)) return;

        ServerPlayer player = play.player;
        MinecraftServer server = player.level().getServer();
        if (server == null) return;
        PacketInfo info = McCompat.customPayload(packet);

        if (!server.isSameThread()) {
            // Network thread. Cancel here either way: a survivor is re-run on the main thread.
            ci.cancel();
            if (engine.allow(player, info, null, Phase.THREAD_SAFE)) {
                server.execute(() -> play.handleCustomPayload(packet));
            }
            return;
        }

        // Main thread: game-state rules.
        if (!engine.allow(player, info, null, Phase.STATE_ONLY)) {
            ci.cancel();
        }
    }
}