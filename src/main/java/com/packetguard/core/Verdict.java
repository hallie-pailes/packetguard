package com.packetguard.core;

/**
 * Result of validating one packet.
 *
 * @param allowed whether the packet may proceed to game logic
 * @param ruleId  id of the rule that rejected it (null when allowed)
 * @param reason  human-readable explanation
 * @param strike  whether this rejection counts toward the player's strike limit.
 *                Benign races (e.g. a click arriving just after the server closed a menu, aka: lag)
 *                are rejected without a strike to avoid punishing lag.
 */
public record Verdict(boolean allowed, String ruleId, String reason, boolean strike) {
    public static final Verdict ALLOW = new Verdict(true, null, null, false);

    public static Verdict reject(String ruleId, String reason) {
        return new Verdict(false, ruleId, reason, true);
    }

    public static Verdict softReject(String ruleId, String reason) {
        return new Verdict(false, ruleId, reason, false);
    }
}
