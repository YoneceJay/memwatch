#!/usr/bin/env python3
"""生成应用图标（纯标准库 PNG 编码，不依赖 PIL）。

用法: 在项目根目录執行  python3 tools/make_icon.py
产物: res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher.png
"""
import os
import struct
import zlib

# 项目根 = 本文件所在目录(tools/)的上一级
BASE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def write_png(path, w, h, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)  # filter type 0
        for (r, g, b) in row:
            raw += bytes((r, g, b))

    def chunk(tag, data):
        out = struct.pack(">I", len(data)) + tag + data
        out += struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        return out

    header = struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)
    blob = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9))
            + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(blob)


def lerp(a, b, t):
    return int(a + (b - a) * t)


def render(size):
    """圆角方块底 + 几个色点，纯占位，换成自己的图标直接覆盖 png 即可。"""
    rows = []
    r_edge = size * 0.18
    bg_top = (38, 50, 72)
    bg_bot = (18, 24, 36)
    for y in range(size):
        row = []
        for x in range(size):
            # 圆角遮罩
            dx = min(x, size - 1 - x)
            dy = min(y, size - 1 - y)
            inside = True
            if dx < r_edge and dy < r_edge:
                cx = r_edge - dx
                cy = r_edge - dy
                inside = (cx * cx + cy * cy) <= r_edge * r_edge
            if not inside:
                row.append((0, 0, 0))
                continue
            t = y / max(1, size - 1)
            color = [lerp(bg_top[i], bg_bot[i], t) for i in range(3)]

            # 三个色点：主色、强调色、点缀色
            for (fx, fy, fr, c) in ((0.36, 0.38, 0.13, (86, 172, 255)),
                                    (0.64, 0.46, 0.10, (255, 176, 64)),
                                    (0.46, 0.66, 0.08, (96, 208, 128))):
                cx, cy, cr = size * fx, size * fy, size * fr
                if (x - cx) ** 2 + (y - cy) ** 2 <= cr * cr:
                    color = list(c)
            row.append((color[0], color[1], color[2]))
        rows.append(row)
    return rows


def main():
    targets = {
        "res/mipmap-mdpi": 48,
        "res/mipmap-hdpi": 72,
        "res/mipmap-xhdpi": 96,
        "res/mipmap-xxhdpi": 144,
        "res/mipmap-xxxhdpi": 192,
    }
    for folder, size in targets.items():
        out_dir = os.path.join(BASE, folder)
        os.makedirs(out_dir, exist_ok=True)
        path = os.path.join(out_dir, "ic_launcher.png")
        write_png(path, size, size, render(size))
        print("生成", os.path.relpath(path, BASE), size)


if __name__ == "__main__":
    main()
