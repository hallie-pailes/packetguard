package com.packetguard.rules;

import com.packetguard.config.GuardConfig;
import com.packetguard.core.PacketInfo;
import com.packetguard.core.PacketKind;
import com.packetguard.core.Rule;
import com.packetguard.core.Verdict;
import com.packetguard.core.Visibility;
import net.minecraft.server.level.ServerPlayer;

/**
 * Field-range sanity. Anything here could also be done by a network proxy (PACKET visibility),
 * and the proxy baseline implements exactly the same bounds from the same config section.
 */
public final class StructuralRule implements Rule {
    @Override public String id() { return "structural"; }

    @Override public Visibility visibility() { return Visibility.PACKET; }

    @Override public boolean appliesTo(PacketKind kind) {
        return kind == PacketKind.CONTAINER_CLICK || kind == PacketKind.CREATIVE_SLOT;
    }

    @Override
    public Verdict check(ServerPlayer player, PacketInfo info, Ctx ctx) {
        GuardConfig.Structural st = ctx.config().structural;
        int slot = info.slot();
        if (slot != PacketInfo.NO_SLOT && (slot < st.minSlot || slot > st.maxSlot)) {
            return Verdict.reject(id(), "slot index out of any plausible range: " + slot);
        }
        if (info.kind() == PacketKind.CONTAINER_CLICK
                && st.requireNonNegativeContainerId && info.containerId() < 0) {
            return Verdict.reject(id(), "negative container id: " + info.containerId());
        }
        return Verdict.ALLOW;
    }
}
