"""Minecraft Java wire-format primitives (no compression, no encryption).

The baseline requires a test server with ``online-mode=false`` and
``network-compression-threshold=-1`` so frames are plain: <VarInt length><packet id VarInt><fields>.
"""
from __future__ import annotations

import struct
from typing import List, NamedTuple


class ProtocolError(Exception):
    """The byte stream cannot be a valid Minecraft stream."""


class NeedMore(Exception):
    """Not enough bytes yet."""


class ParseError(ProtocolError):
    """A packet's fields could not be read (truncated or invalid)."""


class FrameTooLarge(ProtocolError):
    pass


MAX_FRAME_HARD = 2_097_151  # protocol maximum for a serverbound packet (3-byte VarInt)


def encode_varint(n: int) -> bytes:
    n &= 0xFFFFFFFF
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def decode_varint(buf, pos: int = 0):
    """Returns (unsigned 32-bit value, new position). Raises NeedMore / ProtocolError."""
    result = 0
    shift = 0
    i = pos
    while True:
        if i >= len(buf):
            raise NeedMore()
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result & 0xFFFFFFFF, i
        shift += 7
        if shift >= 35:
            raise ProtocolError("VarInt longer than 5 bytes")


def to_signed32(v: int) -> int:
    return v - (1 << 32) if v & 0x80000000 else v


class Frame(NamedTuple):
    raw: bytes      # exactly what was on the wire, length prefix included
    payload: bytes  # packet id + fields


class FrameBuffer:
    """Splits a TCP byte stream into length-prefixed frames."""

    def __init__(self, max_frame: int = MAX_FRAME_HARD):
        self.buf = bytearray()
        self.max_frame = max_frame

    def feed(self, data: bytes) -> List[Frame]:
        self.buf += data
        frames: List[Frame] = []
        while True:
            try:
                length, pos = decode_varint(self.buf, 0)
            except NeedMore:
                break
            if length > self.max_frame:
                raise FrameTooLarge(f"frame of {length} bytes exceeds hard limit {self.max_frame}")
            if len(self.buf) - pos < length:
                break
            end = pos + length
            frames.append(Frame(bytes(self.buf[:end]), bytes(self.buf[pos:end])))
            del self.buf[:end]
        return frames


def frame(payload: bytes) -> bytes:
    return encode_varint(len(payload)) + payload


def decode_position(v: int):
    """Block position packed in an unsigned 64-bit value: x(26) z(26) y(12)."""
    x = v >> 38
    z = (v >> 12) & 0x3FFFFFF
    y = v & 0xFFF
    if x >= 1 << 25:
        x -= 1 << 26
    if z >= 1 << 25:
        z -= 1 << 26
    if y >= 1 << 11:
        y -= 1 << 12
    return x, y, z


def encode_position(x: int, y: int, z: int) -> int:
    return ((x & 0x3FFFFFF) << 38) | ((z & 0x3FFFFFF) << 12) | (y & 0xFFF)


class Reader:
    def __init__(self, data: bytes, pos: int = 0):
        self.data = data
        self.pos = pos

    def _take(self, n: int) -> bytes:
        if self.pos + n > len(self.data):
            raise ParseError("truncated packet")
        b = self.data[self.pos:self.pos + n]
        self.pos += n
        return b

    def varint(self) -> int:
        try:
            v, self.pos = decode_varint(self.data, self.pos)
        except NeedMore:
            raise ParseError("truncated VarInt") from None
        return to_signed32(v)

    def short(self) -> int: return struct.unpack(">h", self._take(2))[0]
    def ushort(self) -> int: return struct.unpack(">H", self._take(2))[0]
    def byte(self) -> int: return struct.unpack(">b", self._take(1))[0]
    def ubyte(self) -> int: return self._take(1)[0]
    def int32(self) -> int: return struct.unpack(">i", self._take(4))[0]
    def int64(self) -> int: return struct.unpack(">q", self._take(8))[0]
    def bool_(self) -> bool: return self._take(1)[0] != 0
    def float32(self) -> float: return struct.unpack(">f", self._take(4))[0]
    def float64(self) -> float: return struct.unpack(">d", self._take(8))[0]

    def string(self, max_len: int = 32767) -> str:
        n = self.varint()
        if n < 0 or n > max_len * 4:
            raise ParseError(f"string length {n} out of range")
        try:
            s = self._take(n).decode("utf-8")
        except UnicodeDecodeError:
            raise ParseError("invalid UTF-8 in string") from None
        if len(s) > max_len:
            raise ParseError("string too long")
        return s

    def position(self):
        return decode_position(struct.unpack(">Q", self._take(8))[0])


class Writer:
    def __init__(self):
        self.b = bytearray()

    def varint(self, n): self.b += encode_varint(n); return self
    def short(self, n): self.b += struct.pack(">h", n); return self
    def byte(self, n): self.b += struct.pack(">b", n); return self
    def ubyte(self, n): self.b += struct.pack(">B", n); return self
    def int32(self, n): self.b += struct.pack(">i", n); return self
    def int64(self, n): self.b += struct.pack(">q", n); return self
    def bool_(self, v): self.b += b"\x01" if v else b"\x00"; return self
    def float32(self, f): self.b += struct.pack(">f", f); return self
    def raw(self, data: bytes): self.b += data; return self

    def string(self, s: str):
        data = s.encode("utf-8")
        self.varint(len(data))
        self.b += data
        return self

    def position(self, x, y, z):
        self.b += struct.pack(">Q", encode_position(x, y, z))
        return self

    def bytes(self) -> bytes:
        return bytes(self.b)


_READERS = {
    "varint": Reader.varint, "short": Reader.short, "ushort": Reader.ushort, "byte": Reader.byte,
    "ubyte": Reader.ubyte, "int": Reader.int32, "long": Reader.int64, "bool": Reader.bool_,
    "float": Reader.float32, "double": Reader.float64, "string": Reader.string,
    "position": Reader.position,
}


def parse_layout(data: bytes, pos: int, layout: List[str]) -> dict:
    """Reads the leading fields described by ``["type name", ...]``. Trailing bytes are ignored."""
    r = Reader(data, pos)
    out = {}
    for entry in layout:
        try:
            typ, name = entry.split(None, 1)
            fn = _READERS[typ]
        except (ValueError, KeyError):
            raise ValueError(f"bad layout entry {entry!r}") from None
        out[name] = fn(r)
    return out
