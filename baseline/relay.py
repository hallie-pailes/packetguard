"""Protocol-aware TCP relay: the network-proxy baseline.

    client  <-->  relay (this file)  <-->  Minecraft server

Requirements on the test server: online-mode=false, network-compression-threshold=-1
(the relay refuses to run if the server enables compression or encryption, because it could not
read the packets any more; that is itself a limitation of the proxy approach, see ARCHITECTURE.md).

Modes
  passthrough  forward everything, evaluate nothing (control: measures proxy cost alone)
  monitor      evaluate and log, but forward everything (like the mod's MONITOR mode)
  filter       drop packets that fail a rule (like the mod's ENFORCE mode)
Orthogonal: --inject attacks.json sends scripted attack packets into the upstream stream after the
real client reaches PLAY; injected packets pass through the same evaluation as real ones.

Outputs (same schema as the mod, plus "source":"proxy"): metrics.csv and events.jsonl in --out.
"""
from __future__ import annotations

import argparse
import asyncio
import csv
import json
import os
import sys
import time
from collections import Counter
from dataclasses import dataclass
from typing import Optional

from .mcwire import (Frame, FrameBuffer, ProtocolError, ParseError, Reader, frame, parse_layout,
                     decode_varint, NeedMore, to_signed32)
from .profile import Profile
from .rules import ConnState, ProxyRules, Rejection

HANDSHAKE, STATUS, LOGIN, CONFIG, PLAY = "HANDSHAKE", "STATUS", "LOGIN", "CONFIG", "PLAY"


class Unsupported(ProtocolError):
    """Server enabled compression or encryption; the proxy cannot read packets."""


# ------------------------------------------------------------------------------ metrics / events
class ProxyMetrics:
    def __init__(self):
        self.evaluated = Counter()
        self.rejected = Counter()
        self.by_rule = Counter()
        self.min_vis = Counter()
        self.buckets = Counter()
        self.lat = []
        self.parse_errors = 0
        self.unclassified = 0

    def record(self, kind: str, nanos: int, rej: Optional[Rejection]):
        self.evaluated[kind] += 1
        self.lat.append(nanos)
        self.buckets[max(0, nanos).bit_length()] += 1
        if rej:
            self.rejected[kind] += 1
            self.by_rule[rej.rule_id] += 1
            self.min_vis[rej.visibility] += 1

    def percentile(self, p: float) -> int:
        if not self.lat:
            return 0
        s = sorted(self.lat)
        return s[min(len(s) - 1, int(p * len(s)))]

    def flush(self, path: str):
        tmp = path + ".tmp"
        with open(tmp, "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(["section", "key", "value"])
            for k, v in sorted(self.evaluated.items()): w.writerow(["evaluated", k, v])
            for k, v in sorted(self.rejected.items()): w.writerow(["rejected", k, v])
            for k, v in sorted(self.by_rule.items()): w.writerow(["rejected_by_rule", k, v])
            for k in ("PACKET", "CONNECTION", "GAME_STATE"):
                w.writerow(["min_visibility_of_rejections", k, self.min_vis.get(k, 0)])
            w.writerow(["latency_ns", "p50", self.percentile(0.5)])
            w.writerow(["latency_ns", "p99", self.percentile(0.99)])
            for k, v in sorted(self.buckets.items()): w.writerow(["latency_bucket_log2_ns", k, v])
            w.writerow(["proxy", "parse_errors", self.parse_errors])
            w.writerow(["proxy", "unclassified_play_packets", self.unclassified])
        os.replace(tmp, path)


class EventSink:
    def __init__(self, path: Optional[str]):
        self.f = open(path, "a", buffering=1) if path else None

    def write(self, row: dict):
        if self.f:
            self.f.write(json.dumps(row, separators=(",", ":")) + "\n")

    def close(self):
        if self.f:
            self.f.close()


# ------------------------------------------------------------------------------ session logic
@dataclass
class Decision:
    action: str                      # "forward" | "drop" | "close"
    rejection: Optional[Rejection] = None
    kind: Optional[str] = None


class Session:
    """Per-connection state machine. No sockets: unit-testable with plain bytes."""

    def __init__(self, profile: Profile, rules: ProxyRules, mode: str, metrics: ProxyMetrics,
                 events: EventSink, conn_id: int = 0, now_ms=lambda: int(time.time() * 1000)):
        self.p, self.rules, self.mode, self.m, self.ev = profile, rules, mode, metrics, events
        self.conn_id, self.now_ms = conn_id, now_ms
        self.state = HANDSHAKE
        self.conn = ConnState()
        self.player = f"conn{conn_id}"
        self.last_container_id: Optional[int] = None

    @staticmethod
    def _packet_id(payload: bytes):
        v, pos = decode_varint(payload, 0)
        return to_signed32(v), pos

    # clientbound: only the login phase matters
    def server_frame(self, fr: Frame):
        if self.state != LOGIN:
            return
        try:
            pid, _ = self._packet_id(fr.payload)
        except (NeedMore, ProtocolError):
            return
        if pid == self.p.cb_set_compression:
            raise Unsupported("server enabled compression; set network-compression-threshold=-1")
        if pid == self.p.cb_encryption_request:
            raise Unsupported("server requested encryption; set online-mode=false")

    def client_frame(self, fr: Frame, injected: bool = False) -> Decision:
        try:
            pid, pos = self._packet_id(fr.payload)
        except (NeedMore, ProtocolError):
            self.m.parse_errors += 1
            return Decision("forward")
        st = self.state
        if st == HANDSHAKE:
            if pid == self.p.handshake_id:
                try:
                    r = Reader(fr.payload, pos)
                    r.varint(); r.string(); r.ushort()
                    nxt = r.varint()
                    self.state = STATUS if nxt == 1 else LOGIN
                except ParseError:
                    self.m.parse_errors += 1
            return Decision("forward")
        if st == LOGIN:
            if pid == self.p.login_start_id:
                try:
                    self.player = Reader(fr.payload, pos).string(16)
                except ParseError:
                    pass
            elif pid == self.p.login_ack_id:
                self.state = CONFIG
            return Decision("forward")
        if st == CONFIG:
            if pid == self.p.config_finish_ack_id:
                self.state = PLAY
            return Decision("forward")
        if st != PLAY:
            return Decision("forward")
        return self._play(fr, pid, pos, injected)

    def _play(self, fr: Frame, pid: int, pos: int, injected: bool) -> Decision:
        kind = self.p.kind_for(pid)
        if kind is None:
            self.m.unclassified += 1
            return Decision("forward")
        if self.mode == "passthrough":
            return Decision("forward", kind=kind)
        t0 = time.perf_counter_ns()
        try:
            fields = parse_layout(fr.payload, pos, self.p.play[kind].layout)
        except ParseError:
            self.m.parse_errors += 1
            return Decision("forward", kind=kind)   # fail open: a baseline that blocks on parse trouble would be unfair
        if kind == "CONTAINER_CLICK":
            self.last_container_id = fields.get("container_id")
        rej = self.rules.check(self.conn, kind, fields, len(fr.payload))
        self.m.record(kind, time.perf_counter_ns() - t0, rej)
        if rej is None:
            return Decision("forward", kind=kind)
        blocked = self.mode == "filter"
        self.ev.write({"timeMillis": self.now_ms(), "source": "proxy", "player": self.player,
                       "kind": kind, "rule": rej.rule_id, "reason": rej.reason,
                       "rejectedBy": [rej.rule_id], "minVisibility": rej.visibility,
                       "containerId": fields.get("container_id"), "slot": fields.get("slot"),
                       "channel": fields.get("channel"), "mode": self.mode, "blocked": blocked,
                       "injected": injected})
        if not blocked:
            return Decision("forward", rej, kind)
        if self.rules.add_strike(self.conn):
            return Decision("close", rej, kind)
        return Decision("drop", rej, kind)


# ------------------------------------------------------------------------------ asyncio plumbing
class Relay:
    def __init__(self, args, profile: Profile, rules: ProxyRules):
        self.args, self.profile, self.rules = args, profile, rules
        self.metrics = ProxyMetrics()
        self.events = EventSink(os.path.join(args.out, "events.jsonl"))
        self.next_id = 0
        self.attacks = []
        if args.inject:
            with open(args.inject) as f:
                self.attacks = json.load(f)["attacks"]
        self.attack_log = open(os.path.join(args.out, "attack_log.jsonl"), "a", buffering=1) if self.attacks else None

    async def handle(self, creader, cwriter):
        self.next_id += 1
        sess = Session(self.profile, self.rules, self.args.mode, self.metrics, self.events, self.next_id)
        try:
            sreader, swriter = await asyncio.open_connection(self.args.upstream_host, self.args.upstream_port)
        except OSError as e:
            print(f"upstream unreachable: {e}", file=sys.stderr)
            cwriter.close()
            return
        done = asyncio.Event()
        tasks = [asyncio.create_task(self._c2s(sess, creader, swriter, done)),
                 asyncio.create_task(self._s2c(sess, sreader, cwriter, done))]
        if self.attacks:
            tasks.append(asyncio.create_task(self._inject(sess, swriter, done)))
        await done.wait()
        for t in tasks:
            t.cancel()
        for w in (cwriter, swriter):
            w.close()

    async def _c2s(self, sess, reader, up, done):
        fb = FrameBuffer()
        try:
            while True:
                data = await reader.read(65536)
                if not data:
                    break
                for fr in fb.feed(data):
                    d = sess.client_frame(fr)
                    if d.action == "close":
                        return
                    if d.action == "forward":
                        up.write(fr.raw)
                await up.drain()
        except (ProtocolError, ConnectionError, OSError):
            pass
        finally:
            done.set()

    async def _s2c(self, sess, reader, down, done):
        fb = FrameBuffer()
        try:
            while True:
                data = await reader.read(65536)
                if not data:
                    break
                if sess.state == LOGIN:
                    for fr in fb.feed(data):
                        sess.server_frame(fr)
                down.write(data)
                await down.drain()
        except Unsupported as e:
            print(f"FATAL: {e}", file=sys.stderr)
        except (ProtocolError, ConnectionError, OSError):
            pass
        finally:
            done.set()

    async def _inject(self, sess, up, done):
        from . import attacks as A
        while sess.state != PLAY:
            await asyncio.sleep(0.05)
        for spec in self.attacks:
            await asyncio.sleep(spec.get("delay_s", 0))
            t0 = int(time.time() * 1000)
            sent = delivered = 0
            for _ in range(spec.get("count", 1)):
                raw = A.build_packet(self.profile, spec, sess.last_container_id)
                payload = _strip_length(raw)
                d = sess.client_frame(Frame(raw, payload), injected=True)
                sent += 1
                if d.action == "forward":
                    up.write(raw)
                    delivered += 1
                elif d.action == "close":
                    break
                if spec.get("interval_ms"):
                    await up.drain()
                    await asyncio.sleep(spec["interval_ms"] / 1000)
            await up.drain()
            self.attack_log.write(json.dumps({"attack": spec["name"], "class": spec.get("class", spec["name"]),
                                              "kind": spec.get("kind"), "startMillis": t0,
                                              "endMillis": int(time.time() * 1000), "sent": sent,
                                              "reachedServer": delivered, "source": "proxy"}) + "\n")


def _strip_length(raw: bytes) -> bytes:
    _, pos = decode_varint(raw, 0)
    return raw[pos:]


async def _periodic_flush(relay: Relay, path: str, every: float):
    while True:
        await asyncio.sleep(every)
        relay.metrics.flush(path)


async def amain(args):
    profile = Profile.load(args.profile)
    if args.mode != "passthrough" or args.inject:
        missing = profile.missing_ids()
        if missing:
            sys.exit(f"profile {args.profile} has null packet ids for {missing}; fill them in first")
        if not profile.verified and not args.allow_unverified:
            sys.exit("profile is not marked verified (use --allow-unverified for experiments only)")
    with open(args.config) as f:
        cfg = json.load(f)
    os.makedirs(args.out, exist_ok=True)
    relay = Relay(args, profile, ProxyRules(cfg))
    server = await asyncio.start_server(relay.handle, args.listen_host, args.listen_port)
    mpath = os.path.join(args.out, "metrics.csv")
    flusher = asyncio.create_task(_periodic_flush(relay, mpath, 5.0))
    print(f"relay {args.mode} on {args.listen_host}:{args.listen_port} -> "
          f"{args.upstream_host}:{args.upstream_port}", flush=True)
    try:
        async with server:
            await server.serve_forever()
    finally:
        flusher.cancel()
        relay.metrics.flush(mpath)
        relay.events.close()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--listen-host", default="0.0.0.0")
    ap.add_argument("--listen-port", type=int, default=25566)
    ap.add_argument("--upstream-host", default="127.0.0.1")
    ap.add_argument("--upstream-port", type=int, default=25565)
    ap.add_argument("--mode", choices=["passthrough", "monitor", "filter"], default="monitor")
    ap.add_argument("--profile", default=os.path.join(os.path.dirname(__file__), "profiles", "template.json"))
    ap.add_argument("--config", required=True, help="the mod's config.json (shared thresholds)")
    ap.add_argument("--out", default="proxy_out")
    ap.add_argument("--inject", help="attacks.json: scripted attack packets to inject after PLAY starts")
    ap.add_argument("--allow-unverified", action="store_true")
    args = ap.parse_args(argv)
    try:
        asyncio.run(amain(args))
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
