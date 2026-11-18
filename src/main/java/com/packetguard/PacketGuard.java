package com.packetguard;

import com.packetguard.core.RuleEngine;

/** Static holder so mixins can reach the engine. Set once at mod initialization. */
public final class PacketGuard {
    private static volatile RuleEngine engine;

    private PacketGuard() {}

    static void install(RuleEngine e) { engine = e; }

    /** May be null very early in startup; mixins must tolerate that. */
    public static RuleEngine engine() { return engine; }
}
