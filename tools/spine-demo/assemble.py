"""Assemble the per-operator in-battle demo assets (crops, GIFs, contact sheet).

Reads the raw 1691x1003 battle frames captured by capture-battle.ps1 and the
battle log (which prints the deployed operator's screen foot position), then:
  * <out>/<id>-battle.gif   - cropped, the operator fighting in a REAL battle
  * <out>/<id>-battle.png   - one still of the same crop
  * <out>/battle-contact.png - all operators on one sheet

ASCII-only source on purpose (Windows PowerShell/Python console encodings).
"""

import glob
import os
import re
import sys

from PIL import Image, ImageDraw

SHOTS = os.path.join("target", "demo-shots")
OUT = os.path.join("docs", "骨骼动画检查", "img")

# crop window relative to the operator's screen foot position
# UP is generous (weapons/hair fly above the head), DOWN has to clear the feet:
# the battle draws the sprite at (tile center + 0.3 tile), so the foot sits ~35px
# below the logged y at this zoom.
LEFT, RIGHT, UP, DOWN = 215, 215, 300, 150


def read_text(path):
    """The child JVM writes its console output in the platform charset (GBK here)."""
    raw = open(path, "rb").read()
    for enc in ("utf-8", "gbk", "cp936", "latin-1"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    return raw.decode("latin-1", "replace")


def find_coords(log_text, op_id):
    """Return (x, y, px) of the operator's foot on screen, or None."""
    # log line: [诊断] 屏幕坐标 reed = 1367,580 格宽 116
    pat = re.compile(r"屏幕坐标\s+(\S+)\s*=\s*(-?\d+)\s*,\s*(-?\d+)\s*格宽\s*(-?\d+)")
    for m in pat.finditer(log_text):
        if m.group(1) == op_id:
            return int(m.group(2)), int(m.group(3)), int(m.group(4))
    return None


def crop_box(x, y, size):
    w, h = size
    left = max(0, x - LEFT)
    right = min(w, x + RIGHT)
    top = max(0, y - UP)
    bottom = min(h, y + DOWN)
    return left, top, right, bottom


def main(ids):
    os.makedirs(OUT, exist_ok=True)
    stills = []
    missing = []
    for op_id in ids:
        src = os.path.join(SHOTS, op_id)
        log = os.path.join(SHOTS, op_id + ".log")
        frames = sorted(glob.glob(os.path.join(src, "frame-*.png")))
        if not frames or not os.path.exists(log):
            missing.append(op_id)
            print("MISSING", op_id, len(frames))
            continue
        coords = find_coords(read_text(log), op_id)
        if coords is None:
            missing.append(op_id)
            print("NO-COORDS", op_id)
            continue
        x, y, px = coords
        first = Image.open(frames[0])
        box = crop_box(x, y, first.size)
        crops = [Image.open(f).crop(box) for f in frames]

        # GIFs live in the repo: downscale, then quantize ALL frames against ONE palette
        # built from the first frames. A per-frame adaptive palette re-dithers the static
        # map background every frame, which both looks noisy and destroys inter-frame
        # compression (measured: 1.6MB -> 340KB for the same clip).
        size = (int(crops[0].width * 0.7), int(crops[0].height * 0.7))
        small = [im.convert("RGB").resize(size, Image.LANCZOS) for im in crops]
        strip = Image.new("RGB", (size[0] * min(4, len(small)), size[1]))
        for i, im in enumerate(small[:4]):
            strip.paste(im, (size[0] * i, 0))
        palette = strip.quantize(colors=128, method=Image.MEDIANCUT)
        gif_frames = [im.quantize(palette=palette, dither=Image.FLOYDSTEINBERG) for im in small]
        gif = os.path.join(OUT, op_id + "-battle.gif")
        gif_frames[0].save(gif, save_all=True, append_images=gif_frames[1:], duration=55,
                           loop=0, optimize=True, disposal=1)
        still = crops[int(len(crops) * 0.7)]
        still_path = os.path.join(OUT, op_id + "-battle.png")
        still.save(still_path)
        stills.append((op_id, still_path))
        print("OK", op_id, "frames=%d" % len(crops), "box=%s" % (box,),
              "gif=%.0fKB" % (os.path.getsize(gif) / 1024.0))

    # contact sheet: 5 columns
    if stills:
        cols = min(5, len(stills))
        rows = (len(stills) + cols - 1) // cols
        tw, th = 300, 280
        sheet = Image.new("RGB", (cols * tw, rows * th + 24), (18, 18, 20))
        d = ImageDraw.Draw(sheet)
        d.text((8, 6), "YesterCar - in-battle operator renders (real battle, %d operators)" % len(stills),
               fill=(235, 235, 235))
        for i, (op_id, path) in enumerate(stills):
            im = Image.open(path).convert("RGB")
            im.thumbnail((tw - 8, th - 26))
            cx = (i % cols) * tw + (tw - im.width) // 2
            cy = 24 + (i // cols) * th + (th - 26 - im.height) // 2
            sheet.paste(im, (cx, cy))
            d.text(((i % cols) * tw + 6, 24 + (i // cols) * th + th - 20), op_id,
                   fill=(250, 220, 120))
        path = os.path.join(OUT, "battle-contact.png")
        sheet.save(path)
        print("contact:", path, sheet.size)

    if missing:
        print("MISSING TOTAL:", len(missing), missing)
        return 1
    return 0


def states_contact():
    """Contact sheet of the six-state strips produced by OperatorDemoSheet."""
    src = os.path.join("target", "demo-states")
    files = sorted(glob.glob(os.path.join(src, "*-states.png")))
    if not files:
        print("no state strips in", src)
        return 0
    # copy each strip next to the battle clips so the report is self-contained
    for f in files:
        Image.open(f).save(os.path.join(OUT, os.path.basename(f)))
    ims = [Image.open(f).convert("RGB") for f in files]
    scale = 0.45
    tw = int(ims[0].width * scale)
    th = int(ims[0].height * scale)
    cols = 3
    rows = (len(ims) + cols - 1) // cols
    sheet = Image.new("RGB", (cols * tw, rows * th + 22), (16, 16, 18))
    d = ImageDraw.Draw(sheet)
    d.text((8, 5), "OperatorDemoSheet - same animator + painter as in battle (%d operators)" % len(ims),
           fill=(235, 235, 235))
    for i, im in enumerate(ims):
        im = im.resize((tw, th), Image.LANCZOS)
        sheet.paste(im, ((i % cols) * tw, 22 + (i // cols) * th))
    path = os.path.join(OUT, "states-contact.png")
    sheet.save(path)
    print("states contact:", path, sheet.size)
    return 0


if __name__ == "__main__":
    arg_ids = sys.argv[1].split(",") if len(sys.argv) > 1 else None
    if arg_ids is None:
        arg_ids = sorted(
            d for d in os.listdir(SHOTS)
            if os.path.isdir(os.path.join(SHOTS, d)) and "," not in d
        )
    rc = main(arg_ids)
    states_contact()
    sys.exit(rc)
