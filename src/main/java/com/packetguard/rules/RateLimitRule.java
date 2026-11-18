package com.packetguard.rules;

import com.packetguard.config.GuardConfig;
import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * Per-player, per-packet-kind token bucket, plus an optional per-channel bucket for mod
 * payloads that have a policy. Needs only connection history (CONNECTION visibility), so a proxy
 * can do it too, and it is thread-safe (it also runs on the network thread).
 */
public final class RateLimitRule implements Rule {
    @Override public String id() { return "rate_limit"; }

    @Override public Visibility visibility() { return Visibility.CONNECTION; }

    @Override public boolean appliesTo(PacketKind kind) { return true; }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        if (!ctx.state().tryConsume(info.kind(), ctx.tick(), ctx.config())) {
            return Verdict.reject(id(), "rate budget exceeded for " + info.kind());
        }
        if (info.kind() == PacketKind.CUSTOM_PAYLOAD && info.channel() != null) {
            GuardConfig.PayloadPolicy policy = ctx.config().payloadPolicies.get(info.channel());
            if (policy != null && policy.maxPerSecond > 0) {
                double perTick = policy.maxPerSecond / 20.0;
                double burst = policy.burst > 0 ? policy.burst : policy.maxPerSecond;
                if (!ctx.state().tryConsumeChannel(info.channel(), ctx.tick(), burst, perTick)) {
                    return Verdict.reject(id(), "per-channel rate exceeded for " + info.channel());
                }
            }
        }
        return Verdict.ALLOW;
    }
}
