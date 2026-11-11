#!/usr/bin/env python3
"""从 assets/ui/icon/dafeiyu-mobile.png 生成安卓启动图标。

为什么需要这个脚本：`assets/ui/` 是**设计参考目录**（HTML 稿、预览图、字体），
不在安卓资源路径上——APK 只编译 `android/app/src/main/res/` 下的东西。
把 PNG 丢进 assets/ui/icon/ 不会进包，清单里的 `@drawable/ic_launcher` 还是旧鲸鱼。
所以图标必须以真正的 mipmap 资源形式生成。

用法：python3 scripts/make-launcher-icons.py
"""

from PIL import Image, ImageDraw
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / 'assets/ui/icon/dafeiyu-mobile.png'
RES = ROOT / 'android/app/src/main/res'

# 源图是 2048×2048、圆角半径 416 的圆角方图，四角是白底。白底在启动器上会变成
# 白方块，所以按圆角矩形抠成透明，交给系统遮罩。
SRC_SIZE = 2048
CORNER_RADIUS = 416

# 自适应图标：108dp 画布，中心 72dp 是可见区、66dp 是安全区。
# 插图缩到 72%，圆形遮罩只会切掉边缘 4dp，人物头部不会被裁。
ADAPTIVE_CANVAS_DP = 108
ADAPTIVE_CONTENT_RATIO = 0.72

# 兜底底色 = 源图左上角那个深蓝，和白底替换后与插图边缘自然衔接。
BACKDROP = (23, 41, 94)

# 传统图标各密度边长（48dp 基准）
LEGACY_DP = 48
DENSITIES = {
    'mdpi': 1.0,
    'hdpi': 1.5,
    'xhdpi': 2.0,
    'xxhdpi': 3.0,
    'xxxhdpi': 4.0,
}


def rounded_alpha(size: int, radius: int) -> Image.Image:
    """超采样 4 倍再缩回来，边缘才不会有锯齿。"""
    scale = 4
    big = Image.new('L', (size * scale, size * scale), 0)
    ImageDraw.Draw(big).rounded_rectangle(
        (0, 0, size * scale - 1, size * scale - 1), radius=radius * scale, fill=255
    )
    return big.resize((size, size), Image.LANCZOS)


def circle_alpha(size: int) -> Image.Image:
    scale = 4
    big = Image.new('L', (size * scale, size * scale), 0)
    ImageDraw.Draw(big).ellipse((0, 0, size * scale - 1, size * scale - 1), fill=255)
    return big.resize((size, size), Image.LANCZOS)


def main() -> None:
    src = Image.open(SRC).convert('RGB')
    if src.size != (SRC_SIZE, SRC_SIZE):
        raise SystemExit(f'源图尺寸变了：{src.size}，请重新量圆角半径与缩放比例')

    art = src.convert('RGBA')
    art.putalpha(rounded_alpha(SRC_SIZE, CORNER_RADIUS))

    for name, factor in DENSITIES.items():
        out_dir = RES / f'mipmap-{name}'
        out_dir.mkdir(parents=True, exist_ok=True)

        side = round(LEGACY_DP * factor)
        flat = art.resize((side, side), Image.LANCZOS)
        # 传统图标没有背景层，四角补底色，否则启动器上是透明洞。
        square = Image.new('RGBA', (side, side), BACKDROP + (255,))
        square.alpha_composite(flat)
        square.convert('RGB').save(out_dir / 'ic_launcher.png')

        # 圆形版：再套一层圆形遮罩。
        round_icon = square.copy()
        round_icon.putalpha(circle_alpha(side))
        round_icon.save(out_dir / 'ic_launcher_round.png')

        # 自适应前景层：108dp 画布，插图居中缩到 72%。
        canvas = round(ADAPTIVE_CANVAS_DP * factor)
        inner = round(canvas * ADAPTIVE_CONTENT_RATIO)
        fg = Image.new('RGBA', (canvas, canvas), (0, 0, 0, 0))
        fg.alpha_composite(art.resize((inner, inner), Image.LANCZOS),
                           ((canvas - inner) // 2, (canvas - inner) // 2))
        fg.save(out_dir / 'ic_launcher_foreground.png')

        print(f'mipmap-{name}: legacy {side}px, adaptive {canvas}px (content {inner}px)')

    anydpi = RES / 'mipmap-anydpi-v26'
    anydpi.mkdir(parents=True, exist_ok=True)
    (anydpi / 'ic_launcher.xml').write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/ic_launcher_background" />\n'
        '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />\n'
        '</adaptive-icon>\n',
        encoding='utf-8',
    )
    (anydpi / 'ic_launcher_round.xml').write_text(
        (anydpi / 'ic_launcher.xml').read_text(encoding='utf-8'), encoding='utf-8'
    )
    print('mipmap-anydpi-v26: ic_launcher.xml + ic_launcher_round.xml')


if __name__ == '__main__':
    main()
