# -*- coding: utf-8 -*-
"""iOS 앱 아이콘(1024, 불투명) + 스플래시(2732 유니버설) 생성 → Xcode 애셋 교체"""
from PIL import Image, ImageDraw, ImageFont
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ICONSET = os.path.join(HERE, "ios/App/App/Assets.xcassets/AppIcon.appiconset")
SPLASHSET = os.path.join(HERE, "ios/App/App/Assets.xcassets/Splash.imageset")
SER = "C:/Windows/Fonts/batang.ttc"; MB = "C:/Windows/Fonts/malgunbd.ttf"

def serif(sz):
    try: return ImageFont.truetype(SER, sz, index=0)
    except Exception: return ImageFont.truetype(MB, sz)

def grad(w, h, c1, c2):
    img = Image.new("RGB", (w, h), c1); d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / h
        d.line([(0, y), (w, y)], fill=tuple(int(c1[i]+(c2[i]-c1[i])*t) for i in range(3)))
    return img

def ring(d, cx, cy, r, color, w):
    off = r * 0.52
    d.ellipse([cx-off-r, cy-r, cx-off+r, cy+r], outline=color, width=w)
    d.ellipse([cx+off-r, cy-r, cx+off+r, cy+r], outline=color, width=w)

# ---------- 앱 아이콘 1024 (수퍼샘플 4096 → 다운스케일, 알파 없음) ----------
S = 4096
img = grad(S, S, (248, 243, 234), (238, 224, 202))
d = ImageDraw.Draw(img)
ring(d, S/2, S*0.355, S*0.145, (176, 122, 92), int(S*0.026))
f = serif(int(S*0.23))
tw = d.textlength("이음", font=f)
d.text((S/2 - tw/2, S*0.565), "이음", font=f, fill=(112, 78, 56))
icon = img.resize((1024, 1024), Image.LANCZOS)
icon.save(os.path.join(ICONSET, "AppIcon-512@2x.png"), "PNG")
print("AppIcon 1024 saved (opaque)")

# ---------- 스플래시 2732x2732 (중앙 안전영역에 브랜드) ----------
S2 = 2732
sp = grad(S2, S2, (247, 243, 235), (240, 229, 212))
d = ImageDraw.Draw(sp)
ring(d, S2/2, S2*0.44, 130, (200, 150, 120), 30)
f2 = serif(300)
tw2 = d.textlength("이음", font=f2)
d.text((S2/2 - tw2/2, S2*0.50), "이음", font=f2, fill=(122, 88, 66))
f3 = ImageFont.truetype(MB, 84)
t3 = "마 음 을   잇 다"
tw3 = d.textlength(t3, font=f3)
d.text((S2/2 - tw3/2, S2*0.64), t3, font=f3, fill=(160, 132, 80))
for n in ["splash-2732x2732.png", "splash-2732x2732-1.png", "splash-2732x2732-2.png"]:
    sp.save(os.path.join(SPLASHSET, n), "PNG")
print("Splash 2732 saved x3")
