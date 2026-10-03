#!/usr/bin/env python3
"""Turns libplacebo's composition of the fixture into the picture KiteFFmpeg's composer writes.

libplacebo writes BT.2020 R'G'B' with the PQ curve as three planes of 32-bit floats per frame,
G then B then R, which is FFmpeg's gbrpf32le. This writes what the composer writes: 10-bit Y'CbCr
4:2:0 in limited range, luma from each pixel, and chroma sited on the left column of each 2x2
block, a quarter of each neighbouring column and half of its own, each column averaged over the
block's two rows.

Usage: expected.py <gbrpf32le> <width> <height> <frames> <out.yuv>
"""
import struct
import sys


def code(v, lo, hi):
    c = int(v + 0.5) if v >= 0 else -int(-v + 0.5)
    return max(lo, min(hi, c))


def main():
    src, w, h, frames, out_path = sys.argv[1], int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4]), sys.argv[5]
    data = open(src, 'rb').read()
    plane = w * h
    cw, ch = (w + 1) // 2, (h + 1) // 2
    out = bytearray()
    for f in range(frames):
        g, b, r = (struct.unpack_from('<%df' % plane, data, (f * 3 + p) * plane * 4) for p in range(3))
        luma = [0.2627 * r[i] + 0.6780 * g[i] + 0.0593 * b[i] for i in range(plane)]
        cb = [(b[i] - luma[i]) / 1.8814 for i in range(plane)]
        cr = [(r[i] - luma[i]) / 1.4746 for i in range(plane)]
        ys = [code(64 + 876 * v, 64, 940) for v in luma]
        out += struct.pack('<%dH' % plane, *ys)
        for chroma in (cb, cr):
            samples = []
            for by in range(ch):
                rows = [2 * by, min(2 * by + 1, h - 1)]
                column = lambda x: sum(chroma[y * w + x] for y in rows) / 2
                for bx in range(cw):
                    x = 2 * bx
                    right = column(min(x + 1, w - 1))
                    left = column(x - 1) if x > 0 else right
                    samples.append(code(512 + 896 * (0.25 * left + 0.5 * column(x) + 0.25 * right), 64, 960))
            out += struct.pack('<%dH' % len(samples), *samples)
    open(out_path, 'wb').write(out)


if __name__ == '__main__':
    main()
