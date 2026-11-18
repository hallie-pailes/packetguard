"""The rules a network proxy can implement, using the SAME thresholds as the mod.

Visibility levels mirror the mod's ``Visibility`` enum:
  PACKET      decidable from this packet's bytes   -> frame_size, structural
  CONNECTION  needs per-connection history          -> rate_limit
There is deliberately no GAME_STATE rule here: a proxy cannot see the open menu, the game mode,
or where the server thinks the player is. That limit is the point of the comparison.

Thresholds come from the mod's own config.json (sections ``structural``, ``rates``,
``payloadPolicies``, ``strikeLimit``...), so both systems are configured from one file.
"""
from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Callable, Dict, Optional

TICKS_PER_SECOND = 20.0


@dataclass(frozen=True)
class Rejection:
    rule_id: str
    reason: str
    visibility: str      # "PACKET" or "CONNECTION"
    strike: bool = True


class Bucket:
    """Token bucket refilled by wall-clock time (a proxy cannot see the server's tick counter)."""

    def __init__(self, capacity: float, refill_per_sec: float, now: float):
        self.capacity = capacity
        self.refill = refill_per_sec
        self.tokens = capacity
        self.last = now

    def take(self, now: float) -> bool:
        if now > self.last:
            self.tokens = min(self.capacity, self.tokens + (now - self.last) * self.refill)
            self.last = now
        if self.tokens >= 1.0:
            self.tokens -= 1.0
            return True
        return False


@dataclass
class ConnState:
    """Per-connection history (this is all the 'memory' a proxy has)."""
    buckets: Dict[str, Bucket] = field(default_factory=dict)
    channel_buckets: Dict[str, Bucket] = field(default_factory=dict)
    strikes: int = 0
    window_start: Optional[float] = None


class ProxyRules:
    def __init__(self, cfg: dict, clock: Callable[[], float] = time.monotonic):
        self.cfg = cfg
        self.clock = clock
        st = cfg.get("structural", {})
        self.min_slot = st.get("minSlot", -999)
        self.max_slot = st.get("maxSlot", 4096)
        self.require_nonneg_container = st.get("requireNonNegativeContainerId", True)
        self.max_frame = st.get("maxFrameBytes", 32768)
        self.rates = cfg.get("rates", {})
        self.policies = cfg.get("payloadPolicies", {}) or {}
        self.strike_limit = cfg.get("strikeLimit", 20)
        self.strike_window_s = cfg.get("strikeWindowTicks", 1200) / TICKS_PER_SECOND
        self.kick = cfg.get("kickOnStrikeLimit", True)
        self.disabled = set(cfg.get("disabledRules", []) or [])

    # -- evaluation, cheapest first; the first rejection decides (same as the mod) ----------------
    def check(self, conn: ConnState, kind: str, fields: dict, payload_len: int) -> Optional[Rejection]:
        if "frame_size" not in self.disabled and payload_len > self.max_frame:
            return Rejection("frame_size", f"packet of {payload_len} bytes exceeds {self.max_frame}", "PACKET")
        if "structural" not in self.disabled:
            r = self._structural(kind, fields)
            if r:
                return r
        if "rate_limit" not in self.disabled:
            r = self._rate(conn, kind, fields)
            if r:
                return r
        return None

    def _structural(self, kind: str, f: dict) -> Optional[Rejection]:
        slot = f.get("slot")
        if kind in ("CONTAINER_CLICK", "CREATIVE_SLOT") and slot is not None:
            if slot < self.min_slot or slot > self.max_slot:
                return Rejection("structural", f"slot index out of any plausible range: {slot}", "PACKET")
        if kind == "CONTAINER_CLICK" and self.require_nonneg_container and f.get("container_id", 0) < 0:
            return Rejection("structural", f"negative container id: {f['container_id']}", "PACKET")
        return None

    def _rate(self, conn: ConnState, kind: str, f: dict) -> Optional[Rejection]:
        now = self.clock()
        b = conn.buckets.get(kind)
        if b is None:
            rc = self.rates.get(kind, {"capacity": 100, "refillPerTick": 10})
            b = conn.buckets[kind] = Bucket(rc["capacity"], rc["refillPerTick"] * TICKS_PER_SECOND, now)
        if not b.take(now):
            return Rejection("rate_limit", f"rate budget exceeded for {kind}", "CONNECTION")
        if kind == "CUSTOM_PAYLOAD":
            channel = f.get("channel")
            pol = self.policies.get(channel) if channel else None
            if pol and pol.get("maxPerSecond", 0) > 0:
                per_sec = pol["maxPerSecond"]
                burst = pol.get("burst", 0) or per_sec
                cb = conn.channel_buckets.get(channel)
                if cb is None:
                    cb = conn.channel_buckets[channel] = Bucket(burst, per_sec, now)
                if not cb.take(now):
                    return Rejection("rate_limit", f"per-channel rate exceeded for {channel}", "CONNECTION")
        return None

    # -- strikes: returns True when the connection should be closed --------------------------------
    def add_strike(self, conn: ConnState) -> bool:
        now = self.clock()
        if conn.window_start is None or now - conn.window_start > self.strike_window_s:
            conn.window_start = now
            conn.strikes = 0
        conn.strikes += 1
        return self.kick and conn.strikes >= self.strike_limit
