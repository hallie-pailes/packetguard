package com.packetguard.rules;

import com.packetguard.config.GuardConfig;
import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * State-aware protection for mod-defined custom payloads.
 *
 * <p>Mods register their own network channels, and vanilla Minecraft performs no checks on them.
 * For every channel the operator lists under {@code payloadPolicies}, this rule enforces:
 * the sender is alive and not a spectator, a menu is open if the channel needs one, and the
 * menu's block is within range. None of this can be decided from bytes: a proxy sees a channel
 * id and a length, never the sender's menu or game mode.
 *
 * <p>The per-channel <em>rate</em> limit ({@code maxPerSecond}/{@code burst}) lives in
 * {@code RateLimitRule}, not here: it needs only connection history, so a proxy could do it too,
 * and keeping it at CONNECTION visibility keeps the proxy comparison honest.
 *
 * <p>Runs only in the {@code Phase.STATE_ONLY} pass on the main server thread, because it reads
 * player and menu state.
 *
 * <p>Limitation: it does not parse payload contents, so it cannot stop bugs that live inside a
 * payload handler, such as unsafe deserialization (BleedingPipe-style flaws).
 */
public final class PayloadPolicyRule implements Rule {
    @Override public String id() { return "payload_policy"; }

    @Override public boolean appliesTo(PacketKind kind) { return kind == PacketKind.CUSTOM_PAYLOAD; }

    @Override public Visibility visibility() { return Visibility.GAME_STATE; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        if (info.channel() == null) return Verdict.ALLOW;
        GuardConfig.PayloadPolicy p = ctx.config().payloadPolicies.get(info.channel());
        if (p == null) return Verdict.ALLOW;

        if (p.requireAlive && !player.isAlive()) {
            return Verdict.reject(id(), info.channel() + ": sender is not alive");
        }
        if (p.forbidSpectator && player.isSpectator()) {
            return Verdict.reject(id(), info.channel() + ": sender is a spectator");
        }
        if (p.requireOpenMenu && player.containerMenu == player.inventoryMenu) {
            // Soft: a payload can legitimately arrive just after the server closed the menu.
            return Verdict.softReject(id(), info.channel() + ": requires an open menu");
        }
        if (p.maxMenuDistance > 0) {
            double d = MenuGeometry.distanceToBackingBlock(player);
            if (d >= 0 && d > p.maxMenuDistance) {
                return Verdict.reject(id(), String.format(
                        "%s: menu block %.1f away (limit %.1f)", info.channel(), d, p.maxMenuDistance));
            }
        }
        return Verdict.ALLOW;
    }
}
