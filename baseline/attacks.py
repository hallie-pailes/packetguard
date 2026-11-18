"""Attack packet builders for the evaluation (used by the relay's --inject mode and by tests).

An attack spec (see attacks.example.json) is a dict:
  name, class        label used in the attack log
  kind               CONTAINER_CLICK | CREATIVE_SLOT | BLOCK_USE | BLOCK_ACTION | CUSTOM_PAYLOAD | RAW
  fields             kind-specific values (container_id may be "snoop" = last id the real client used)
  count, interval_ms how many packets and the gap between them
  delay_s            wait before starting
  expected_min_visibility  PACKET | CONNECTION | GAME_STATE: the weakest information level that can
                           in principle recognise the attack (documentation + sanity check in analyze.py)
Byte layouts after the profile-defined leading fields are version-sensitive and come from the
profile's ``attack_encoding`` section.
"""
from __future__ import annotations

from .mcwire import Writer, frame
from .profile import Profile


def _id(profile: Profile, kind: str) -> int:
    pid = profile.play[kind].id
    if pid is None:
        raise ValueError(f"profile has no packet id for {kind}")
    return pid


def build_packet(profile: Profile, spec: dict, last_container_id=None) -> bytes:
    kind = spec["kind"]
    f = spec.get("fields", {})
    enc = profile.attack
    w = Writer()
    if kind == "RAW":
        return frame(bytes.fromhex(f["hex"]))
    w.varint(_id(profile, kind))
    if kind == "CONTAINER_CLICK":
        cid = f.get("container_id", 0)
        if cid == "snoop":
            cid = last_container_id if last_container_id is not None else 0
        w.varint(cid).varint(f.get("state_id", 0)).short(f.get("slot", 0))
        w.raw(bytes.fromhex(enc.get("container_click_tail_hex") or ""))
    elif kind == "CREATIVE_SLOT":
        w.short(f.get("slot", 36))
        w.raw(bytes.fromhex(enc.get("creative_item_hex") or ""))
    elif kind == "BLOCK_USE":
        x, y, z = f.get("pos", [0, 64, 0])
        w.varint(f.get("hand", 0)).position(x, y, z).varint(f.get("face", 1))
        w.float32(0.5).float32(0.5).float32(0.5).bool_(False)
        if enc.get("block_use_world_border_flag", True):
            w.bool_(False)
        w.varint(f.get("sequence", 0))
    elif kind == "BLOCK_ACTION":
        x, y, z = f.get("pos", [0, 64, 0])
        w.varint(f.get("status", 0)).position(x, y, z).byte(f.get("face", 1)).varint(f.get("sequence", 0))
    elif kind == "CUSTOM_PAYLOAD":
        w.string(f.get("channel", "packetguard:test"))
        w.raw(b"\x00" * f.get("data_bytes", 0))
    else:
        raise ValueError(f"unknown attack kind {kind}")
    return frame(w.bytes())
