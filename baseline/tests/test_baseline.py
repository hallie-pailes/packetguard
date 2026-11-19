"""Tests for the baseline. Uses a TEST profile with made-up ids (never a real game version)."""
import asyncio, json, os, sys, tempfile, unittest, copy
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", ".."))
from baseline import mcwire as W
from baseline.profile import Profile
from baseline.rules import ProxyRules, ConnState
from baseline import relay as R, attacks as A

HERE = os.path.dirname(__file__)
IDS = {"CONTAINER_CLICK": 0x10, "CREATIVE_SLOT": 0x11, "BLOCK_USE": 0x12, "BLOCK_ACTION": 0x13, "CUSTOM_PAYLOAD": 0x14}


def test_profile():
    p = Profile.load(os.path.join(HERE, "..", "profiles", "template.json"))
    for k, i in IDS.items():
        p.play[k].id = i
    p.verified = True
    return p


def cfg():
    with open(os.path.join(HERE, "fixtures", "default_config.json")) as f:
        return json.load(f)


class Clock:
    def __init__(self): self.t = 100.0
    def __call__(self): return self.t


def mk_session(mode="filter", c=None, clock=None):
    clock = clock or Clock()
    m, ev = R.ProxyMetrics(), R.EventSink(None)
    s = R.Session(test_profile(), ProxyRules(c or cfg(), clock), mode, m, ev, 1, now_ms=lambda: 0)
    s.state = R.PLAY
    return s, m, clock


def fr(profile, kind, **fields):
    raw = A.build_packet(profile, {"kind": kind, "fields": fields})
    _, pos = W.decode_varint(raw, 0)
    return W.Frame(raw, raw[pos:])


class Wire(unittest.TestCase):
    def test_varint_roundtrip(self):
        for n in (0, 1, 127, 128, 25565, 2**31 - 1, -1):
            v, pos = W.decode_varint(W.encode_varint(n))
            self.assertEqual(W.to_signed32(v), n)

    def test_framebuffer_split(self):
        data = W.frame(b"\x01abc") + W.frame(b"\x02")
        fb = W.FrameBuffer()
        out = []
        for i in range(len(data)):           # one byte at a time
            out += fb.feed(data[i:i + 1])
        self.assertEqual([f.payload for f in out], [b"\x01abc", b"\x02"])

    def test_position(self):
        for p in [(0, 64, 0), (-5, -60, 12), (5000, 100, -5000)]:
            self.assertEqual(W.decode_position(W.encode_position(*p)), p)

    def test_truncated(self):
        with self.assertRaises(W.ParseError):
            W.parse_layout(b"\x00", 0, ["varint a", "short b"])


class Rules(unittest.TestCase):
    def test_default_config_loads_and_shared(self):
        r = ProxyRules(cfg())
        self.assertEqual(r.max_slot, 4096)
        self.assertEqual(r.rates["CONTAINER_CLICK"]["capacity"], 120)

    def test_structural_and_frame(self):
        r = ProxyRules(cfg())
        c = ConnState()
        self.assertEqual(r.check(c, "CONTAINER_CLICK", {"container_id": 0, "slot": 30000}, 10).rule_id, "structural")
        self.assertEqual(r.check(c, "CONTAINER_CLICK", {"container_id": -1, "slot": 0}, 10).rule_id, "structural")
        self.assertEqual(r.check(c, "CUSTOM_PAYLOAD", {"channel": "x"}, 99999).rule_id, "frame_size")
        self.assertIsNone(r.check(c, "CONTAINER_CLICK", {"container_id": 0, "slot": 5}, 10))

    def test_rate_uses_wall_clock(self):
        clk = Clock()
        r = ProxyRules(cfg(), clk)
        c = ConnState()
        ok = sum(r.check(c, "CONTAINER_CLICK", {"container_id": 0, "slot": 0}, 5) is None for _ in range(200))
        self.assertEqual(ok, 120)                       # burst capacity
        clk.t += 1.0                                    # 1 s = 20 ticks * 10/tick refill... capped by capacity
        self.assertIsNone(r.check(c, "CONTAINER_CLICK", {"container_id": 0, "slot": 0}, 5))

    def test_proxy_cannot_see_state(self):
        """Valid-looking packet that violates game state (menu not open, far block) passes the proxy."""
        r = ProxyRules(cfg())
        c = ConnState()
        self.assertIsNone(r.check(c, "CONTAINER_CLICK", {"container_id": 77, "slot": 5}, 10))
        self.assertIsNone(r.check(c, "BLOCK_USE", {"pos": (5000, 64, 5000)}, 10))

    def test_disabled_rule(self):
        c2 = cfg(); c2["disabledRules"] = ["structural"]
        r = ProxyRules(c2)
        self.assertIsNone(r.check(ConnState(), "CONTAINER_CLICK", {"container_id": 0, "slot": 30000}, 10))

    def test_strike_close(self):
        c2 = cfg(); c2["strikeLimit"] = 3
        r = ProxyRules(c2, Clock()); c = ConnState()
        self.assertEqual([r.add_strike(c) for _ in range(3)], [False, False, True])


class SessionTests(unittest.TestCase):
    def test_state_machine_to_play(self):
        p = test_profile(); m = R.ProxyMetrics()
        s = R.Session(p, ProxyRules(cfg()), "filter", m, R.EventSink(None), 1)
        w = W.Writer().varint(p.handshake_id).varint(770).string("localhost").raw(b"\x63\xdd").varint(2).bytes()
        s.client_frame(W.Frame(W.frame(w), w)); self.assertEqual(s.state, R.LOGIN)
        w = W.Writer().varint(p.login_start_id).string("Steve").raw(b"\0" * 16).bytes()
        s.client_frame(W.Frame(W.frame(w), w)); self.assertEqual(s.player, "Steve")
        w = W.Writer().varint(p.login_ack_id).bytes()
        s.client_frame(W.Frame(W.frame(w), w)); self.assertEqual(s.state, R.CONFIG)
        w = W.Writer().varint(p.config_finish_ack_id).bytes()
        s.client_frame(W.Frame(W.frame(w), w)); self.assertEqual(s.state, R.PLAY)

    def test_compression_refused(self):
        p = test_profile()
        s = R.Session(p, ProxyRules(cfg()), "filter", R.ProxyMetrics(), R.EventSink(None), 1)
        s.state = R.LOGIN
        pl = W.Writer().varint(p.cb_set_compression).varint(256).bytes()
        with self.assertRaises(R.Unsupported):
            s.server_frame(W.Frame(W.frame(pl), pl))

    def test_filter_drops_monitor_forwards(self):
        p = test_profile()
        s, m, _ = mk_session("filter")
        self.assertEqual(s.client_frame(fr(p, "CONTAINER_CLICK", container_id=0, slot=30000)).action, "drop")
        s2, m2, _ = mk_session("monitor")
        self.assertEqual(s2.client_frame(fr(p, "CONTAINER_CLICK", container_id=0, slot=30000)).action, "forward")
        self.assertEqual(m2.rejected["CONTAINER_CLICK"], 1)

    def test_passthrough_evaluates_nothing(self):
        p = test_profile()
        s, m, _ = mk_session("passthrough")
        self.assertEqual(s.client_frame(fr(p, "CONTAINER_CLICK", container_id=0, slot=30000)).action, "forward")
        self.assertEqual(sum(m.evaluated.values()), 0)

    def test_parse_error_fails_open(self):
        p = test_profile(); s, m, _ = mk_session("filter")
        bad = W.Writer().varint(IDS["CONTAINER_CLICK"]).bytes()
        self.assertEqual(s.client_frame(W.Frame(W.frame(bad), bad)).action, "forward")
        self.assertEqual(m.parse_errors, 1)

    def test_unknown_id_forwarded(self):
        s, m, _ = mk_session("filter")
        pl = W.Writer().varint(0x7F).bytes()
        self.assertEqual(s.client_frame(W.Frame(W.frame(pl), pl)).action, "forward")
        self.assertEqual(m.unclassified, 1)

    def test_strikes_close_connection(self):
        c2 = cfg(); c2["strikeLimit"] = 3
        p = test_profile(); s, _, _ = mk_session("filter", c2)
        acts = [s.client_frame(fr(p, "CONTAINER_CLICK", container_id=0, slot=30000)).action for _ in range(3)]
        self.assertEqual(acts, ["drop", "drop", "close"])

    def test_injected_clicks_do_not_change_snoop_id(self):
        p = test_profile(); s, _, _ = mk_session("filter")
        s.client_frame(fr(p, "CONTAINER_CLICK", container_id=3, slot=0))                 # real click
        s.client_frame(fr(p, "CONTAINER_CLICK", container_id=-5, slot=0), injected=True) # attack
        self.assertEqual(s.last_container_id, 3)

    def test_metrics_flush_schema(self):
        p = test_profile(); s, m, _ = mk_session("filter")
        s.client_frame(fr(p, "CONTAINER_CLICK", container_id=0, slot=30000))
        with tempfile.TemporaryDirectory() as d:
            m.flush(os.path.join(d, "metrics.csv"))
            txt = open(os.path.join(d, "metrics.csv")).read()
        self.assertTrue(txt.startswith("section,key,value"))
        self.assertIn("rejected_by_rule,structural,1", txt)
        self.assertIn("min_visibility_of_rejections,PACKET,1", txt)
        self.assertIn("min_visibility_of_rejections,GAME_STATE,0", txt)

    def test_attack_builders_roundtrip(self):
        p = test_profile()
        f = fr(p, "BLOCK_USE", pos=[-5, 70, 12])
        pid, pos = f.payload[0], 1
        d = W.parse_layout(f.payload, 1, p.play["BLOCK_USE"].layout)
        self.assertEqual(d["pos"], (-5, 70, 12))
        f = fr(p, "CUSTOM_PAYLOAD", channel="a:b", data_bytes=7)
        self.assertEqual(W.parse_layout(f.payload, 1, p.play["CUSTOM_PAYLOAD"].layout)["channel"], "a:b")


class AttackWireFormat(unittest.TestCase):
    """Each injected packet must parse to EXACTLY its length under the 26.3 codecs
    (checked against the decompiled StreamCodecs), so the server cannot fail to decode it."""
    def _payload(self, kind, **fields):
        p = test_profile()
        raw = A.build_packet(p, {"kind": kind, "fields": fields})
        _, pos = W.decode_varint(raw, 0)
        r = W.Reader(raw[pos:]); r.varint()           # packet id
        return r, raw[pos:]

    def test_container_click(self):
        r, pl = self._payload("CONTAINER_CLICK", container_id=3, slot=7)
        r.varint(); r.varint(); r.short()             # container id, state id, slot
        r.byte(); r.varint(); r.varint(); self.assertFalse(r.bool_())   # button, click type, changed slots (0), carried item absent
        self.assertEqual(r.pos, len(pl))

    def test_block_use(self):
        r, pl = self._payload("BLOCK_USE", pos=[1, 64, 2])
        r.varint(); r.position(); r.varint()          # hand, pos, face
        r.float32(); r.float32(); r.float32(); r.bool_(); r.bool_(); r.varint()  # click xyz, inside, world border, sequence
        self.assertEqual(r.pos, len(pl))

    def test_block_action(self):
        r, pl = self._payload("BLOCK_ACTION", status=0, pos=[1, 64, 2])
        r.varint(); r.position(); r.ubyte(); r.varint()   # action, pos, direction, sequence
        self.assertEqual(r.pos, len(pl))

    def test_creative_slot(self):
        r, pl = self._payload("CREATIVE_SLOT", slot=36)
        r.short(); self.assertEqual(r.varint(), 0)    # empty item stack (count 0)
        self.assertEqual(r.pos, len(pl))

    def test_custom_payload(self):
        r, pl = self._payload("CUSTOM_PAYLOAD", channel="a:b", data_bytes=5)
        self.assertEqual(r.string(), "a:b"); self.assertEqual(len(pl) - r.pos, 5)


class EndToEnd(unittest.IsolatedAsyncioTestCase):
    async def _run(self, mode, inject=None):
        p = test_profile()
        received = bytearray()
        got = asyncio.Event()

        async def upstream(r, w):
            while True:
                d = await r.read(65536)
                if not d: break
                received.extend(d); got.set()
        up = await asyncio.start_server(upstream, "127.0.0.1", 0)
        up_port = up.sockets[0].getsockname()[1]
        d = tempfile.mkdtemp()
        cfgp = os.path.join(d, "c.json"); json.dump(cfg(), open(cfgp, "w"))
        pj = os.path.join(d, "p.json")
        prof = json.load(open(os.path.join(HERE, "..", "profiles", "template.json")))
        prof["verified"] = True
        for k, i in IDS.items(): prof["serverbound"]["play"][k]["id"] = i
        json.dump(prof, open(pj, "w"))
        class Args: pass
        a = Args(); a.mode = mode; a.out = d; a.inject = inject; a.upstream_host = "127.0.0.1"
        a.upstream_port = up_port
        relay = R.Relay(a, Profile.load(pj), ProxyRules(cfg()))
        srv = await asyncio.start_server(relay.handle, "127.0.0.1", 0)
        port = srv.sockets[0].getsockname()[1]
        r, w = await asyncio.open_connection("127.0.0.1", port)
        def f(pl): return W.frame(pl)
        w.write(f(W.Writer().varint(0).varint(770).string("h").raw(b"\x63\xdd").varint(2).bytes()))
        w.write(f(W.Writer().varint(0).string("Steve").raw(b"\0" * 16).bytes()))
        w.write(f(W.Writer().varint(3).bytes()))   # login ack
        w.write(f(W.Writer().varint(3).bytes()))   # finish config ack
        good = A.build_packet(Profile.load(pj), {"kind": "CONTAINER_CLICK", "fields": {"container_id": 0, "slot": 5}})
        bad = A.build_packet(Profile.load(pj), {"kind": "CONTAINER_CLICK", "fields": {"container_id": 0, "slot": 30000}})
        w.write(good + bad + good)
        await w.drain()
        await asyncio.sleep(0.4)
        w.close(); srv.close(); up.close()
        return bytes(received), good, bad, d, relay

    async def test_filter_drops_only_bad(self):
        recv, good, bad, d, relay = await self._run("filter")
        self.assertEqual(recv.count(good), 2)
        self.assertEqual(recv.count(bad), 0)
        ev = [json.loads(l) for l in open(os.path.join(d, "events.jsonl"))]
        self.assertEqual(len(ev), 1); self.assertEqual(ev[0]["source"], "proxy"); self.assertTrue(ev[0]["blocked"])

    async def test_monitor_forwards_everything(self):
        recv, good, bad, d, relay = await self._run("monitor")
        self.assertEqual(recv.count(bad), 1)
        ev = [json.loads(l) for l in open(os.path.join(d, "events.jsonl"))]
        self.assertFalse(ev[0]["blocked"])

    async def test_inject(self):
        d = tempfile.mkdtemp()
        spec = os.path.join(d, "a.json")
        json.dump({"attacks": [
            {"name": "x_far", "kind": "BLOCK_USE", "fields": {"pos": [5000, 64, 5000]}, "count": 2},
            {"name": "x_slot", "kind": "CONTAINER_CLICK", "fields": {"container_id": 0, "slot": 30000}, "count": 2}]}, open(spec, "w"))
        recv, good, bad, d2, relay = await self._run("filter", inject=spec)
        self.assertEqual(recv.count(bad), 0)          # injected structural attack dropped
        far = A.build_packet(test_profile(), {"kind": "BLOCK_USE", "fields": {"pos": [5000, 64, 5000]}})
        self.assertEqual(recv.count(far), 2)          # state attack passes the proxy: the point of the comparison
        log = [json.loads(l) for l in open(os.path.join(d2, "attack_log.jsonl"))]
        self.assertEqual([(l["attack"], l["sent"], l["reachedServer"]) for l in log],
                         [("x_far", 2, 2), ("x_slot", 2, 0)])


if __name__ == "__main__":
    unittest.main()