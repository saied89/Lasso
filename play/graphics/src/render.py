#!/usr/bin/env python3
"""Renders the Play Store graphics in play/graphics/ with headless Chrome.

Inputs:
  docs/icon.svg                    the app icon (keep it in sync with the launcher icon)
  play/graphics/src/raw/N.png      raw phone screenshots (adb exec-out screencap -p > N.png)

Outputs (in play/graphics/):
  icon-512.png, feature-graphic.png, screenshot-N.png

Usage: python3 play/graphics/src/render.py   (CHROME=/path/to/chrome to override)
"""
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

SRC = Path(__file__).resolve().parent
OUT = SRC.parent
ROOT = OUT.parent.parent
CHROME = os.environ.get("CHROME") or shutil.which("google-chrome") or shutil.which("chromium")

# Caption and subtitle for each raw screenshot, in listing order.
SCREENSHOTS = [
    ("1.png", "Select text in any app", "Even where Android won't let you"),
    ("2.png", "Copy, share or translate", "Drag across words, adjust with handles"),
    ("3.png", "Private by design", "On-device, no internet access, open source"),
]

BASE = (
    "<!doctype html><meta charset=utf-8><style>html,body{margin:0;padding:0;overflow:hidden}"
    "body{font-family:'Google Sans','Roboto','Noto Sans',sans-serif}</style>"
)
BACKGROUND = "linear-gradient(135deg,#E8EEF8,#C9D8F7)"


def icon_svg(size: int, rounded: bool) -> str:
    svg = (ROOT / "docs/icon.svg").read_text()
    svg = svg.replace('width="160" height="160"', f'width="{size}" height="{size}"')
    # Play masks the icon itself, so the uploaded one must be a full square.
    return svg if rounded else svg.replace(' rx="20"', "")


def icon_page() -> str:
    return f"{BASE}<body style='width:512px;height:512px'>{icon_svg(512, rounded=False)}</body>"


def feature_page() -> str:
    return f"""{BASE}<body style='width:1024px;height:500px;background:{BACKGROUND};display:flex;
align-items:center;gap:56px;padding:0 90px;box-sizing:border-box'>
<div style='filter:drop-shadow(0 12px 24px rgba(31,91,224,.25))'>{icon_svg(260, rounded=True)}</div>
<div><div style='font-size:96px;font-weight:600;color:#14254A;letter-spacing:-1px'>Lasso</div>
<div style='font-size:40px;color:#1F5BE0;margin-top:8px;line-height:1.25'>Select text in any app</div></div>
</body>"""


def screenshot_page(image: Path, caption: str, subtitle: str) -> str:
    # 9:16 canvas: Play rejects screenshots whose long side is more than twice the short side.
    return f"""{BASE}<body style='width:1080px;height:1920px;background:{BACKGROUND};text-align:center'>
<div style='padding-top:110px;font-size:72px;font-weight:600;color:#14254A'>{caption}</div>
<div style='font-size:38px;color:#3B5180;margin-top:18px'>{subtitle}</div>
<img src='{image.as_uri()}' style='margin-top:70px;height:1480px;border-radius:44px;
box-shadow:0 24px 60px rgba(20,37,74,.30);border:10px solid #14254A'>
</body>"""


def render(html: str, out: Path, width: int, height: int, tmp: Path) -> None:
    page = tmp / (out.stem + ".html")
    page.write_text(html)
    subprocess.run(
        [CHROME, "--headless", "--disable-gpu", "--hide-scrollbars", "--force-device-scale-factor=1",
         f"--screenshot={out}", f"--window-size={width},{height}", page.as_uri()],
        check=True, capture_output=True, timeout=60,
    )
    print(f"{out.relative_to(ROOT)}  {width}x{height}")


def main() -> None:
    if not CHROME:
        raise SystemExit("Chrome not found. Set CHROME=/path/to/chrome.")
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        render(icon_page(), OUT / "icon-512.png", 512, 512, tmp)
        render(feature_page(), OUT / "feature-graphic.png", 1024, 500, tmp)
        for i, (name, caption, subtitle) in enumerate(SCREENSHOTS, start=1):
            page = screenshot_page(SRC / "raw" / name, caption, subtitle)
            render(page, OUT / f"screenshot-{i}.png", 1080, 1920, tmp)


if __name__ == "__main__":
    main()
