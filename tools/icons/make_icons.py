"""Builds the launcher icons from the logo image.

Usage: python3 tools/icons/make_icons.py tools/icons/streamshield-logo.png app/src/main/res preview.png
(see tools/icons/README.md)

Logo: a dark navy rounded square (vertical gradient) on black, with a red shield in the middle.
Outputs, for mdpi..xxxhdpi:
  mipmap-*/ic_launcher_foreground.png   adaptive icon foreground, 108 dp, opaque full bleed
  mipmap-*/ic_launcher_monochrome.png   themed-icon layer (Android 13+): the shield's shape
  mipmap-*/ic_launcher.png              legacy icon (Android 7.x): the logo's rounded square
  mipmap-*/ic_launcher_round.png        legacy round icon
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

src_path, res_dir, preview_path = sys.argv[1], sys.argv[2], sys.argv[3]
src = np.asarray(Image.open(src_path).convert('RGB')).astype(np.float64)
H, W, _ = src.shape

# Measured on the logo: the rounded square and its corner radius, the shield's box.
SQ_X0, SQ_X1, SQ_Y0, SQ_Y1, SQ_R = 42, 1211, 39, 1211, 300
SH_Y0, SH_Y1 = 164, 1061
CX, CY = (SQ_X0 + SQ_X1) / 2, (SQ_Y0 + SQ_Y1) / 2

# Navy background: a vertical gradient. Fit colour = a + b*y on columns left/right of the shield.
ys = np.arange(140, 1081)
samples = np.concatenate([src[ys, 105:135], src[ys, 1120:1150]], axis=1).mean(axis=1)   # (n, 3)
A = np.stack([np.ones_like(ys, dtype=float), ys.astype(float)], axis=1)
coef, *_ = np.linalg.lstsq(A, samples, rcond=None)                                       # (2, 3)


def navy_at(y):
    return np.clip(coef[0] + coef[1] * np.asarray(y, dtype=float)[..., None], 0, 255)


# Master canvas: 108 dp at 12 px/dp. The shield's height is half the canvas (as in the logo, where
# it is ~77% of the rounded square; the visible part of an adaptive icon is the inner 72 dp).
C = 1296
scale = 0.5 * C / (SH_Y1 - SH_Y0)


def to_canvas(img, size_px, resample=Image.LANCZOS):
    """Places an image in logo coordinates onto a C x C canvas (logo centre -> canvas centre)."""
    w = int(round(img.width * scale))
    h = int(round(img.height * scale))
    scaled = img.resize((w, h), resample)
    canvas = Image.new(scaled.mode, (size_px, size_px), (0,) * len(scaled.getbands()))
    canvas.paste(scaled, (int(round(size_px / 2 - CX * scale)), int(round(size_px / 2 - CY * scale))))
    return canvas


# Rounded-square mask in logo coordinates (4x supersampled), pulled in and feathered for blending.
def rounded_mask(inset, blur):
    ss = 4
    m = Image.new('L', (W * ss, H * ss), 0)
    ImageDraw.Draw(m).rounded_rectangle(
        [(SQ_X0 + inset) * ss, (SQ_Y0 + inset) * ss, (SQ_X1 - inset) * ss, (SQ_Y1 - inset) * ss],
        radius=max(SQ_R - inset, 1) * ss, fill=255)
    m = m.resize((W, H), Image.LANCZOS)
    return m.filter(ImageFilter.GaussianBlur(blur)) if blur else m


# 1. Foreground: gradient everywhere, the logo's square blended in.
canvas_y = np.arange(C)
logo_y = CY + (canvas_y - C / 2) / scale
grad = np.repeat(navy_at(logo_y)[:, None, :], C, axis=1)
logo_rgb = to_canvas(Image.fromarray(src.astype(np.uint8)), C)
blend = np.asarray(to_canvas(rounded_mask(inset=30, blur=18), C, Image.BILINEAR)).astype(float)[..., None] / 255
fg = grad * (1 - blend) + np.asarray(logo_rgb).astype(float) * blend
foreground = Image.fromarray(np.clip(fg, 0, 255).round().astype(np.uint8), 'RGB')

# 2. Monochrome: the shield's coverage from the red channel (shield red >= 160, navy <= 20).
bg_r = navy_at(np.arange(H))[:, 0][:, None]
alpha = np.clip((src[..., 0] - bg_r - 4) / (160 - bg_r - 4), 0, 1)
alpha *= np.asarray(rounded_mask(inset=0, blur=0)).astype(float) / 255
mono_a = to_canvas(Image.fromarray((alpha * 255).round().astype(np.uint8), 'L'), C)
monochrome = Image.merge('RGBA', [Image.new('L', (C, C), 255)] * 3 + [mono_a])

# 3. Legacy square: the logo's rounded square with clear corners, 44 dp inside 48 dp.
side = 1172
box = (int(CX - side / 2), int(CY - side / 2), int(CX - side / 2) + side, int(CY - side / 2) + side)
sq_rgb = Image.fromarray(src.astype(np.uint8)).crop(box)
sq_a = rounded_mask(inset=2, blur=0).crop(box)
legacy_square = Image.merge('RGBA', list(sq_rgb.split()) + [sq_a])

# 4. Legacy round: the foreground's visible 72 dp as a circle, 44 dp inside 48 dp.
inner = C * 72 // 108
off = (C - inner) // 2
circ_rgb = foreground.crop((off, off, off + inner, off + inner))
ss = 4
cm = Image.new('L', (inner * ss, inner * ss), 0)
ImageDraw.Draw(cm).ellipse([ss, ss, inner * ss - ss, inner * ss - ss], fill=255)
legacy_round = Image.merge('RGBA', list(circ_rgb.split()) + [cm.resize((inner, inner), Image.LANCZOS)])


def legacy(img, px):
    out = Image.new('RGBA', (px, px), (0, 0, 0, 0))
    content = int(round(px * 44 / 48))
    out.paste(img.resize((content, content), Image.LANCZOS), ((px - content) // 2, (px - content) // 2))
    return out


DENSITIES = {'mdpi': 1, 'hdpi': 1.5, 'xhdpi': 2, 'xxhdpi': 3, 'xxxhdpi': 4}
for name, f in DENSITIES.items():
    d = f'{res_dir}/mipmap-{name}'
    layer = int(round(108 * f))
    foreground.resize((layer, layer), Image.LANCZOS).save(f'{d}/ic_launcher_foreground.png', optimize=True)
    monochrome.resize((layer, layer), Image.LANCZOS).save(f'{d}/ic_launcher_monochrome.png', optimize=True)
    icon = int(round(48 * f))
    legacy(legacy_square, icon).save(f'{d}/ic_launcher.png', optimize=True)
    legacy(legacy_round, icon).save(f'{d}/ic_launcher_round.png', optimize=True)

# Preview: launcher masks (circle, squircle, rounded square), themed icon, legacy icons.
P = 216   # 108 dp at 2 px/dp
fg_p = foreground.resize((P, P), Image.LANCZOS)
mono_p = monochrome.resize((P, P), Image.LANCZOS)
vis = P * 72 // 108
o = (P - vis) // 2


def masked(layer, shape, tint_bg=None):
    big = 4
    m = Image.new('L', (vis * big, vis * big), 0)
    dr = ImageDraw.Draw(m)
    if shape == 'circle':
        dr.ellipse([0, 0, vis * big - 1, vis * big - 1], fill=255)
    elif shape == 'squircle':
        dr.rounded_rectangle([0, 0, vis * big - 1, vis * big - 1], radius=int(vis * big * 0.38), fill=255)
    else:
        dr.rounded_rectangle([0, 0, vis * big - 1, vis * big - 1], radius=int(vis * big * 0.16), fill=255)
    m = m.resize((vis, vis), Image.LANCZOS)
    crop = layer.crop((o, o, o + vis, o + vis)).convert('RGBA')
    if tint_bg is not None:   # themed icon: pale background, dark tinted shape
        base = Image.new('RGBA', (vis, vis), tint_bg)
        shape_layer = Image.new('RGBA', (vis, vis), (30, 50, 90, 255))
        base.paste(shape_layer, (0, 0), crop.split()[3])
        crop = base
    out = Image.new('RGBA', (vis, vis), (0, 0, 0, 0))
    out.paste(crop, (0, 0), m)
    return out


tiles = [masked(fg_p, 'circle'), masked(fg_p, 'squircle'), masked(fg_p, 'rounded'),
         masked(mono_p, 'circle', tint_bg=(214, 227, 255, 255)),
         legacy(legacy_square, vis), legacy(legacy_round, vis)]
pad = 24
sheet = Image.new('RGB', (pad + len(tiles) * (vis + pad), vis + 2 * pad + 60), (238, 238, 238))
for i, t in enumerate(tiles):
    sheet.paste(t, (pad + i * (vis + pad), pad), t)
# Small sizes on a wallpaper-like dark strip (as on a home screen).
small_y = vis + 2 * pad - 8
dark = Image.new('RGB', (sheet.width, 60), (40, 44, 52))
sheet.paste(dark, (0, small_y))
x = pad
for s in (48, 40, 32):
    t = masked(fg_p, 'circle').resize((s, s), Image.LANCZOS)
    sheet.paste(t, (x, small_y + (60 - s) // 2), t)
    x += s + 16
sheet.save(preview_path)
print('ok', coef.round(4).tolist())
