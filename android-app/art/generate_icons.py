#!/usr/bin/env python3
# AwesomeMe 图标生成脚本(方案 B:蓝紫渐变底 + 白色 `>_`)
# 产物(直接写进 app/src/main/res):
#   - adaptive icon(API 26+):drawable 渐变背景 XML + mipmap-*/ic_launcher_foreground.png(透明底白 >_)
#     + mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml(含 monochrome)
#   - legacy PNG(API 24-25):mipmap-*/{ic_launcher,ic_launcher_round}.png(48~192px,圆角/圆形)
# 用法:python3 generate_icons.py   (PIL + DejaVuSansMono-Bold 即可)
import math
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "..", "app", "src", "main", "res")
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf"

C1 = (0x4F, 0x8C, 0xFF)  # #4F8CFF
C2 = (0x8A, 0x5C, 0xF5)  # #8A5CF5

# adaptive icon 前景只落在中心 66% 安全区
SAFE = 0.66
# legacy 成品里 >_ 占画布比例(与 adaptive 前景一致,视觉统一)
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
# adaptive foreground 按 108dp 画布:mdpi 108 … xxxhdpi 432
FG_SIZES = {k: v * 108 // 48 for k, v in DENSITIES.items()}


def gradient(size, angle_deg=135):
    """线性渐变底:C1 → C2,默认 135°(左上→右下)。"""
    img = Image.new("RGB", (size, size))
    px = img.load()
    a = math.radians(angle_deg)
    dx, dy = math.cos(a), math.sin(a)
    # 投影范围归一化到 [0,1]
    proj = lambda x, y: (x * dx + y * dy)
    p0, p1 = proj(0, 0), proj(size - 1, size - 1)
    for y in range(size):
        for x in range(size):
            t = (proj(x, y) - p0) / (p1 - p0) if p1 != p0 else 0
            t = max(0.0, min(1.0, t))
            px[x, y] = tuple(round(C1[i] + (C2[i] - C1[i]) * t) for i in range(3))
    return img


def draw_prompt(img, scale=1.0):
    """在画布中央按 66% 安全区画白色 `>_`(画布需为正方形)。"""
    size = img.width
    d = ImageDraw.Draw(img)
    font = ImageFont.truetype(FONT, round(size * 0.42 * scale))
    text = ">_"
    box = d.textbbox((0, 0), text, font=font)
    w, h = box[2] - box[0], box[3] - box[1]
    x = (size - w) / 2 - box[0]
    y = (size - h) / 2 - box[1]
    d.text((x, y), text, font=font, fill=(255, 255, 255, 255))


def rounded_mask(size, radius_ratio=0.22, circle=False):
    m = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(m)
    if circle:
        d.ellipse((0, 0, size, size), fill=255)
    else:
        d.rounded_rectangle((0, 0, size, size), radius=size * radius_ratio, fill=255)
    return m


def main():
    # 1) adaptive 前景:透明底 + 白 >_(108dp 系列)
    for name, px in FG_SIZES.items():
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        draw_prompt(fg)
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        fg.save(os.path.join(out, "ic_launcher_foreground.png"))

    # 2) legacy:渐变底 + 白 >_,圆角(ic_launcher)与圆形(ic_launcher_round)两版
    for name, px in DENSITIES.items():
        base = gradient(px).convert("RGBA")
        draw_prompt(base)
        out = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(out, exist_ok=True)
        for suffix, circle in (("ic_launcher", False), ("ic_launcher_round", True)):
            img = base.copy()
            img.putalpha(rounded_mask(px, circle=circle))
            img.save(os.path.join(out, f"{suffix}.png"))

    # 3) 512px 参考大图(给 release/文档用)
    big = gradient(512).convert("RGBA")
    draw_prompt(big)
    big.save(os.path.join(HERE, "icon-512.png"))
    print("icons generated under", RES)


if __name__ == "__main__":
    main()
