package com.packetguard.mixin;

import com.packetguard.PacketGuard;
import com.packetguard.compat.McCompat;
import com.packetguard.core.PacketInfo;
import com.packetguard.core.RuleEngine;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Validation hooks on the server-side play packet handler.
 *
 * <p><b>Threading.</b> Vanilla handlers begin by calling {@code ensureRunningOnSameThread}, which,
 * on the network thread, throws to re-queue the packet onto the main server thread. Injecting at
 * HEAD therefore runs <em>twice</em>: first on the network thread, then on the main thread. We
 * only validate on the main thread, so rules read a consistent game state and validation happens
 * in the same tick as the handler (no time-of-check/time-of-use gap).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
    @Shadow public ServerPlayer player;

    @Unique
    private boolean packetguard$ready() {
        MinecraftServer server = player.level().getServer();
        return server != null && server.isSameThread() && PacketGuard.engine() != null;
    }

    @Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
    private void packetguard$containerClick(ServerboundContainerClickPacket packet, CallbackInfo ci) {
        if (!packetguard$ready()) return;
        RuleEngine engine = PacketGuard.engine();
        PacketInfo info = McCompat.containerClick(packet);
        if (!engine.allow(player, info, () -> McCompat.resyncMenu(player))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
    private void packetguard$creativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
        if (!packetguard$ready()) return;
        RuleEngine engine = PacketGuard.engine();
        PacketInfo info = McCompat.creativeSlot(packet);
        if (!engine.allow(player, info, () -> McCompat.resyncMenu(player))) {
            ci.cancel();
        }
    }

    @Inject(method = "handleUseItemOn", at = @At("HEAD"), cancellable = true)
    private void packetguard$useItemOn(ServerboundUseItemOnPacket packet, CallbackInfo ci) {
        if (!packetguard$ready()) return;
        RuleEngine engine = PacketGuard.engine();
        PacketInfo info = McCompat.useItemOn(packet);
        if (!engine.allow(player, info, () -> McCompat.resyncBlock(player, info.pos(), info.sequence()))) {
            ci.cancel();
        }
    }

    @Inject(method = "handlePlayerAction", at = @At("HEAD"), cancellable = true)
    private void packetguard$playerAction(ServerboundPlayerActionPacket packet, CallbackInfo ci) {
        if (!packetguard$ready()) return;
        PacketInfo info = McCompat.playerAction(packet);
        if (info == null) return; // not a block-breaking action: nothing to validate
        RuleEngine engine = PacketGuard.engine();
        if (!engine.allow(player, info, () -> McCompat.resyncBlock(player, info.pos(), info.sequence()))) {
            ci.cancel();
        }
    }
}
