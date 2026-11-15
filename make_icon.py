#!/usr/bin/env python3
"""Generates a flat, dark launcher icon with a cyan download chevron."""
import struct, zlib, math, os

S = 192
BG = (15, 17, 20)
CYAN = (79, 195, 247)
TRAY = (33, 38, 45)


def rounded_alpha(x, y, r=42):
    # simple rounded square mask
    cx = min(max(x, r), S - r)
    cy = min(max(y, r), S - r)
    d = math.hypot(x - cx, y - cy)
    return max(0.0, min(1.0, (r - d) / 1.5))


rows = []
for y in range(S):
    row = bytearray([0])
    for x in range(S):
        a = rounded_alpha(x, y)
        # chevron: two thick diagonals meeting at the centre + a stem above
        col = BG
        # stem
        if 60 <= y <= 104 and 88 <= x <= 104:
            col = CYAN
        # chevron arms
        dy = y - 104
        if 0 <= dy <= 34:
            left = 88 - dy
            right = 104 + dy
            if abs(x - left) <= 8 or abs(x - right) <= 8:
                col = CYAN
        # tray line
        if 138 <= y <= 146 and 52 <= x <= 140:
            col = TRAY
        r, g, b = col
        row += bytes((r, g, b, int(a * 255)))
    rows.append(bytes(row))

raw = b"".join(rows)


def chunk(tag, data):
    c = struct.pack(">I", len(data)) + tag + data
    return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


ihdr = struct.pack(">IIBBBBB", S, S, 8, 6, 0, 0, 0)
png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
       + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))

out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res", "drawable", "ic_launcher.png")
os.makedirs(os.path.dirname(out), exist_ok=True)
with open(out, "wb") as f:
    f.write(png)
print("wrote", out, len(png), "bytes")