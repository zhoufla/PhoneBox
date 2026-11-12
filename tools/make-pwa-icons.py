#!/usr/bin/env python3
"""生成 PWA 图标（192 / 512 PNG），图形与 res/drawable/ic_launcher.xml 一致。

只用 Python 标准库，生成结果直接提交进 assets/web/，不进构建依赖。
用法：python3 tools/make-pwa-icons.py
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets", "web")

BG = (0x0D, 0x11, 0x17)
GREEN = (0x3F, 0xB9, 0x50)
WHITE = (0xE6, 0xED, 0xF3)

# 108x108 设计稿中的矩形：(x0, y0, x1, y1, color)
SHAPES = [
    (26, 28, 82, 38, GREEN),
    (26, 46, 82, 55, WHITE),
    (26, 63, 82, 72, WHITE),
    (26, 80, 60, 89, WHITE),
]


def chunk(tag, data):
    return (struct.pack(">I", len(data)) + tag + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))


def make_png(size):
    scale = size / 108.0
    rects = [(round(x0 * scale), round(y0 * scale),
              round(x1 * scale), round(y1 * scale), color)
             for x0, y0, x1, y1, color in SHAPES]

    raw = bytearray()
    for y in range(size):
        raw.append(0)  # PNG filter type 0
        for x in range(size):
            color = BG
            for x0, y0, x1, y1, c in rects:
                if x0 <= x < x1 and y0 <= y < y1:
                    color = c
                    break
            raw.extend(color)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))


def main():
    for size in (192, 512):
        path = os.path.join(OUT, "icon-%d.png" % size)
        with open(path, "wb") as f:
            f.write(make_png(size))
        print("generated %s (%d bytes)" % (path, os.path.getsize(path)))


if __name__ == "__main__":
    main()
