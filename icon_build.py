"""
Drop Cleaner 图标生成器 v8 —— 64x64 画布（细节够了）
输出：icon.png (128，2x) / icon_64.png (1x) / icon_256.png (4x 预览)

构图：MC 箱子正面（木框、木纹、横带、灰色锁扣）
      锁下方：绿色三角回收标（三根粗箭头首尾相接）
"""
import math
import os
from PIL import Image

W = H = 64
TRANS = (0, 0, 0, 0)

FRAME = (38, 26, 15)
PLANK = (146, 103, 60)
PLANK_D = (122, 85, 48)
PLANK_L = (170, 124, 76)
BAND = (56, 38, 22)
BAND_D = (44, 30, 17)
LOCK = (120, 120, 120)
LOCK_D = (82, 82, 82)
LOCK_L = (176, 176, 176)

REC_OUT = (26, 60, 18)
REC_M = (86, 170, 56)
REC_L = (168, 224, 128)


class Canvas:
    def __init__(self):
        self.px = [[TRANS for _ in range(W)] for _ in range(H)]

    def put(self, x, y, color):
        x, y = int(round(x)), int(round(y))
        if 0 <= x < W and 0 <= y < H:
            self.px[y][x] = color

    def rect(self, x0, y0, x1, y1, color):
        for y in range(int(y0), int(y1) + 1):
            for x in range(int(x0), int(x1) + 1):
                self.put(x, y, color)

    def stroke(self, x0, y0, x1, y1, width, color):
        steps = int(max(abs(x1 - x0), abs(y1 - y0)) * 6) + 1
        half = width / 2.0
        r = int(math.ceil(half))
        for i in range(steps + 1):
            t = i / steps
            cx = x0 + (x1 - x0) * t
            cy = y0 + (y1 - y0) * t
            for dx in range(-r, r + 1):
                for dy in range(-r, r + 1):
                    if dx * dx + dy * dy <= half * half + 0.35:
                        self.put(cx + dx, cy + dy, color)

    def to_image(self, scale):
        img = Image.new("RGBA", (W, H), TRANS)
        p = img.load()
        for y in range(H):
            for x in range(W):
                c = self.px[y][x]
                p[x, y] = (c[0], c[1], c[2], 255) if c is not TRANS else TRANS
        return img.resize((W * scale, H * scale), Image.NEAREST)


def draw_chest(c):
    c.rect(1, 1, 62, 62, FRAME)
    c.rect(2, 2, 61, 61, PLANK)

    # 上半木纹
    for x, y, w in [(6, 6, 5), (16, 7, 7), (30, 5, 6), (44, 7, 5),
                    (10, 12, 8), (26, 13, 7), (42, 12, 8), (52, 6, 5)]:
        c.rect(x, y, x + w, y + 1, PLANK_L)
    for x, y, w in [(10, 9, 6), (22, 6, 5), (36, 10, 7), (50, 10, 5),
                    (6, 15, 7), (34, 16, 6)]:
        c.rect(x, y, x + w, y, PLANK_D)

    # 中间横带
    c.rect(2, 21, 61, 26, BAND)
    c.rect(2, 21, 61, 21, BAND_D)

    # 下半木纹
    for x, y, w in [(6, 30, 7), (20, 31, 6), (34, 30, 8), (48, 31, 7),
                    (10, 38, 6), (26, 39, 8), (44, 38, 7), (8, 48, 7),
                    (24, 50, 6), (40, 49, 8), (52, 40, 5)]:
        c.rect(x, y, x + w, y + 1, PLANK_L)
    for x, y, w in [(12, 34, 6), (28, 35, 6), (46, 35, 6), (6, 44, 6),
                    (20, 45, 7), (38, 44, 6), (50, 46, 6)]:
        c.rect(x, y, x + w, y, PLANK_D)

    # 锁扣
    c.rect(27, 15, 36, 30, LOCK_D)
    c.rect(28, 16, 35, 29, LOCK)
    c.rect(29, 17, 31, 22, LOCK_L)
    c.rect(31, 25, 33, 27, LOCK_D)


def arrow_on_edge(c, p, q, gap_from_start, gap_to_end, head_len, head_half):
    """沿 p→q 画一段线，末端一个实心三角箭头。"""
    dx, dy = q[0] - p[0], q[1] - p[1]
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy, ux

    ax, ay = p[0] + ux * gap_from_start, p[1] + uy * gap_from_start
    bx, by = q[0] - ux * gap_to_end, q[1] - uy * gap_to_end

    c.stroke(ax, ay, bx, by, 3.4, REC_OUT)
    c.stroke(ax, ay, bx, by, 2.0, REC_M)

    tip = (q[0] - ux * (gap_to_end - head_len), q[1] - uy * (gap_to_end - head_len))
    base = (tip[0] - ux * head_len, tip[1] - uy * head_len)
    a = (base[0] + nx * head_half, base[1] + ny * head_half)
    b = (base[0] - nx * head_half, base[1] - ny * head_half)

    for t in range(0, 25):
        f = t / 24.0
        pa = (tip[0] + (a[0] - tip[0]) * f, tip[1] + (a[1] - tip[1]) * f)
        pb = (tip[0] + (b[0] - tip[0]) * f, tip[1] + (b[1] - tip[1]) * f)
        n = int(max(abs(pb[0] - pa[0]), abs(pb[1] - pa[1])) * 2) + 1
        for s in range(n + 1):
            g = s / n
            c.put(pa[0] + (pb[0] - pa[0]) * g, pa[1] + (pb[1] - pa[1]) * g, REC_M)

    c.stroke(tip[0], tip[1], a[0], a[1], 1.2, REC_OUT)
    c.stroke(tip[0], tip[1], b[0], b[1], 1.2, REC_OUT)


def draw_recycle(c):
    top = (32.0, 36.0)
    right = (46.0, 56.0)
    left = (18.0, 56.0)

    arrow_on_edge(c, top, right, 4.0, 5.0, 5.0, 4.0)
    arrow_on_edge(c, right, left, 4.0, 5.0, 5.0, 4.0)
    arrow_on_edge(c, left, top, 4.0, 5.0, 5.0, 4.0)


def draw():
    c = Canvas()
    draw_chest(c)
    draw_recycle(c)
    return c


def main():
    canvas = draw()
    # 输出到脚本所在目录，换电脑也能跑
    out = os.path.dirname(os.path.abspath(__file__))

    def export(scale, name):
        canvas.to_image(scale).save(out + "\\" + name)

    export(2, "icon.png")        # 128
    export(1, "icon_64.png")     # 64
    export(4, "icon_256.png")    # 256 预览
    print("ok")


if __name__ == "__main__":
    main()
