"""Generates the Vire icon as 32x32 pixel art, in the Ghost Mason / Ortho / Gauge icon style.

Style: dark rounded-square badge (#10161C) with 4-px stepped corners, soft radial glow, a block
character with a cute face, cream 4-point sparkles. Subject: a see-through cyan "ghost" cube (a block
that is only planned, Vire's colour) standing on a lit redstone dust line.

Usage: python tools/make_icon.py  -> media/icon.png (512), media/icon-128.png (128)
"""
import math
import os
import sys

from PIL import Image

N = 32
BG = (16, 22, 28, 255)
GLOW = (14, 48, 60)
TOP = (150, 236, 255, 190)
RIM = (220, 250, 255, 255)
LEFT = (40, 190, 235, 170)
RIGHT = (20, 120, 170, 170)
EDGE = (190, 245, 255, 255)
EYE = (8, 30, 44, 255)
EYE_HI = (240, 252, 255, 255)
DUST_ON = (255, 70, 50, 255)
DUST_HI = (255, 170, 120, 255)
DUST_OFF = (110, 20, 18, 255)
SPARK = (255, 236, 140)


def blend(dst, src):
    """Alpha-composite src (r,g,b,a) over dst (r,g,b,a)."""
    sa = src[3] / 255
    da = dst[3] / 255
    oa = sa + da * (1 - sa)
    if oa == 0:
        return (0, 0, 0, 0)
    c = tuple(round((src[i] * sa + dst[i] * da * (1 - sa)) / oa) for i in range(3))
    return c + (round(oa * 255),)


def corner_cut(x, y):
    """Stepped rounded corners: 4,3,2,1 transparent pixels on rows 0..3 (mirrored)."""
    steps = [4, 3, 2, 1]
    for yy, xx in ((y, x), (N - 1 - y, x), (y, N - 1 - x), (N - 1 - y, N - 1 - x)):
        if yy < 4 and xx < steps[yy]:
            return True
    return False



def main(out_dir):
    px = [[(0, 0, 0, 0)] * N for _ in range(N)]

    def put(x, y, c):
        if 0 <= x < N and 0 <= y < N and not corner_cut(x, y):
            px[y][x] = blend(px[y][x], c) if c[3] < 255 else c

    for y in range(N):
        for x in range(N):
            if corner_cut(x, y):
                continue
            d = math.hypot(x + 0.5 - 16, y + 0.5 - 15) / 15
            t = max(0.0, 1 - d) ** 1.6
            px[y][x] = tuple(round(BG[i] + (GLOW[i] - BG[i]) * t) for i in range(3)) + (255,)

    # redstone dust line under the cube, lit (bright) on the left, fading to off on the right
    for x in range(3, 29):
        t = (x - 3) / 25
        c = tuple(round(DUST_ON[i] + (DUST_OFF[i] - DUST_ON[i]) * t) for i in range(3)) + (255,)
        put(x, 27, c)
        put(x, 26, tuple(round(v * 0.75) for v in c[:3]) + (255,))
    for x in (5, 9, 13):
        put(x, 26, DUST_HI)

    # see-through isometric cube with a face
    xc, yc, W, H, F = 16, 10, 8, 4, 8
    for y in range(N):
        for x in range(N):
            fx, fy = x + 0.5, y + 0.5
            if abs(fx - xc) / W + abs(fy - yc) / H <= 1.0:
                put(x, y, TOP)
                continue
            if xc - W <= fx < xc:
                te = yc + (fx - (xc - W)) / 2
                if te <= fy < te + F:
                    put(x, y, LEFT)
            elif xc <= fx < xc + W:
                te = yc + ((xc + W) - fx) / 2
                if te <= fy < te + F:
                    put(x, y, RIGHT)
    # bright outline: the ghost's edges
    for x in range(xc - W, xc + W):
        fx = x + 0.5
        top = yc + ((fx - (xc - W)) if fx < xc else ((xc + W) - fx)) / 2
        put(x, math.floor(top - 0.01), RIM)
        put(x, math.floor(top - 0.01 + F), EDGE)
    for y in range(int(yc), int(yc + F)):
        put(xc - W, y, EDGE)
        put(xc + W - 1, y, EDGE)
    for y in range(int(yc + H), int(yc + H + F)):
        put(16, y, EDGE)
    for ex, ey in ((10, 16), (13, 17)):
        for dy in range(3):
            put(ex, ey + dy, EYE)
        put(ex, ey, EYE_HI)

    def sparkle(x, y, strong=True):
        put(x, y, SPARK + (255,))
        a = 200 if strong else 140
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            put(x + dx, y + dy, SPARK + (a,))

    sparkle(5, 6, strong=False)
    sparkle(26, 5)

    img = Image.new("RGBA", (N, N))
    img.putdata([c for row in px for c in row])
    os.makedirs(out_dir, exist_ok=True)
    img.resize((512, 512), Image.NEAREST).save(os.path.join(out_dir, "icon.png"))
    img.resize((128, 128), Image.NEAREST).save(os.path.join(out_dir, "icon-128.png"))
    print("wrote", out_dir)


if __name__ == "__main__":
    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    main(sys.argv[1] if len(sys.argv) > 1 else os.path.join(root, "media"))
