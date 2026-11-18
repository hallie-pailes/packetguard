"""Protocol profile: the version-specific packet ids and field layouts the proxy needs.

Packet ids change between Minecraft versions, so they are DATA, not code. The template in
profiles/ ships with the six stable-looking ids filled in and the play-state ids set to null on
purpose: they must be filled in for your exact game version, and the profile marked verified,
before the proxy will filter anything. See ../TESTING.md for how to check a profile.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from typing import Dict, List, Optional

PLAY_KINDS = ("CONTAINER_CLICK", "CREATIVE_SLOT", "BLOCK_USE", "BLOCK_ACTION", "CUSTOM_PAYLOAD")


@dataclass
class PlayEntry:
    id: Optional[int]
    layout: List[str]
    block_statuses: Optional[List[int]] = None  # BLOCK_ACTION only: statuses that count as block breaking


@dataclass
class Profile:
    name: str
    verified: bool
    handshake_id: int
    login_start_id: int
    login_ack_id: int
    config_finish_ack_id: int
    cb_encryption_request: int
    cb_set_compression: int
    play: Dict[str, PlayEntry]
    attack: dict = field(default_factory=dict)

    @classmethod
    def load(cls, path: str) -> "Profile":
        with open(path) as f:
            d = json.load(f)
        sb = d["serverbound"]
        play = {}
        for kind, e in sb["play"].items():
            if kind not in PLAY_KINDS:
                raise ValueError(f"unknown play kind {kind!r}")
            play[kind] = PlayEntry(e.get("id"), e["layout"], e.get("block_statuses"))
        cb = d["clientbound_login"]
        return cls(
            name=d.get("name", path),
            verified=bool(d.get("verified", False)),
            handshake_id=sb["handshake"],
            login_start_id=sb["login_start"],
            login_ack_id=sb["login_acknowledged"],
            config_finish_ack_id=sb["finish_configuration_ack"],
            cb_encryption_request=cb["encryption_request"],
            cb_set_compression=cb["set_compression"],
            play=play,
            attack=d.get("attack_encoding", {}),
        )

    def missing_ids(self) -> List[str]:
        return [k for k, e in self.play.items() if e.id is None]

    def kind_for(self, packet_id: int) -> Optional[str]:
        for kind, e in self.play.items():
            if e.id is not None and e.id == packet_id:
                return kind
        return None
