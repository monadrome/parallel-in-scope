#!/usr/bin/env python3
"""Extract .figure blocks from the CHM blog HTMLs and screenshot each as PNG.

Usage: python3 extract_figures.py
Output: todo/blog/images/<slug>-fig<N>.png
"""
import re
import os
import select
import signal
import subprocess
import sys
import tempfile
import time
from contextlib import contextmanager
from pathlib import Path

CHROME = "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
TODO = Path(__file__).resolve().parent.parent / "todo"
OUT = TODO / "blog" / "images"
WIDTH = 900  # CSS px; close to the article content width (980 - padding)
SCALE = 2    # device scale factor for retina-quality PNGs

FILES = {
    "lock": "chm-lock-mechanism.html",
    "iterator": "chm-iterator-no-duplicate.html",
    "fwd": "chm-forwarding-node.html",
    "sizectl": "chm-sizectl.html",
    "behaviors": "chm-counterintuitive-behaviors.html",
}

PAGE = """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="UTF-8">
<style>
{css}
html, body {{ background: #fff; }}
body {{ padding: 0; }}
article {{ max-width: none; border: 0; border-radius: 0; padding: 12px; }}
</style></head>
<body><article>{figure}</article>
<script>document.title = String(document.body.scrollHeight + 24);</script>
</body></html>"""


def extract_figures(html: str):
    """Return list of full '<div class="figure">...</div>' source blocks."""
    blocks = []
    for m in re.finditer(r'<div class="figure">', html):
        depth, i = 1, m.start()  # the matched <div class="figure"> is already open
        while True:
            nxt_open = html.find("<div", i + 1)
            nxt_close = html.find("</div>", i + 1)
            if nxt_close == -1:
                raise ValueError("unbalanced <div> in figure block")
            if nxt_open != -1 and nxt_open < nxt_close:
                depth += 1
                i = nxt_open
            else:
                depth -= 1
                i = nxt_close
                if depth == 0:
                    blocks.append(html[m.start():nxt_close + len("</div>")])
                    break
    return blocks


@contextmanager
def chrome_process(args):
    chrome = os.environ.get("CHROME", CHROME)
    if not Path(chrome).is_file():
        raise FileNotFoundError(f"Chrome executable not found: {chrome}; set CHROME to its path")
    with tempfile.TemporaryDirectory(prefix="extract-figures-chrome-") as profile:
        process = subprocess.Popen(
            [chrome, "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
             f"--user-data-dir={profile}"] + args,
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            start_new_session=True,
        )
        try:
            yield process
        finally:
            def group_exists():
                try:
                    os.killpg(process.pid, 0)
                except ProcessLookupError:
                    return False
                except PermissionError:
                    return True
                return True

            if group_exists():
                try:
                    os.killpg(process.pid, signal.SIGTERM)
                except (ProcessLookupError, PermissionError):
                    pass
                deadline = time.monotonic() + 2
                while time.monotonic() < deadline and group_exists():
                    time.sleep(0.05)
                if group_exists():
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except (ProcessLookupError, PermissionError):
                        pass
            try:
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


def chrome_dom(args):
    """Run headless Chrome for --dump-dom; kill once '</html>' has been read.

    Chrome 151 on macOS renders fine but never exits on its own, so we
    terminate the process after the DOM has been fully written to stdout.
    """
    with chrome_process(args) as p:
        if p.stdout is None:
            raise RuntimeError("Chrome stdout pipe is unavailable")
        fd = p.stdout.fileno()
        os.set_blocking(fd, False)
        deadline = time.monotonic() + 30
        raw = bytearray()
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError("Chrome DOM timed out")
            ready, _, _ = select.select([fd], [], [], remaining)
            if not ready:
                raise TimeoutError("Chrome DOM timed out")
            try:
                chunk = os.read(fd, 65536)
            except BlockingIOError:
                continue
            if not chunk:
                code = p.poll()
                raise RuntimeError(f"Chrome exited before complete HTML (code {code})")
            raw.extend(chunk)
            if b"</html>" in raw:
                break
    return bytes(raw).decode("utf-8", errors="replace")


def chrome_shot(args, out: Path):
    """Run headless Chrome for --screenshot; kill once the PNG is written."""
    if out.exists():
        out.unlink()
    with chrome_process(args) as p:
        for _ in range(300):  # up to 30s
            time.sleep(0.1)
            if out.exists() and out.stat().st_size > 0:
                time.sleep(0.3)  # let the write finish
                return
        raise TimeoutError(f"screenshot timed out: {out}")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="extract-figures-") as temp:
        tmp = Path(temp)
        total = 0
        for slug, fname in FILES.items():
            html = (TODO / fname).read_text(encoding="utf-8")
            style = re.search(r"<style>(.*?)</style>", html, re.S)
            if style is None:
                raise ValueError(f"{fname}: missing <style> block")
            css = style.group(1)
            figures = extract_figures(html)
            print(f"{fname}: {len(figures)} figures")
            for n, fig in enumerate(figures, 1):
                page = tmp / f"{slug}-{n}.html"
                page.write_text(PAGE.format(css=css, figure=fig), encoding="utf-8")
                # 1) measure height via --dump-dom (script writes it into <title>)
                try:
                    dom = chrome_dom(["--virtual-time-budget=2000", "--dump-dom",
                                      f"--window-size={WIDTH},1000",
                                      f"file://{page}"])
                except (TimeoutError, RuntimeError) as error:
                    print(f"  fig {n}: FAILED to measure height: {error}", file=sys.stderr)
                    continue
                m = re.search(r"<title>(\d+)</title>", dom)
                if not m:
                    print(f"  fig {n}: FAILED to measure height", file=sys.stderr)
                    continue
                height = int(m.group(1))
                # 2) screenshot at measured size
                out = OUT / f"{slug}-fig{n}.png"
                try:
                    chrome_shot([f"--screenshot={out}",
                                 f"--window-size={WIDTH},{height}",
                                 f"--force-device-scale-factor={SCALE}",
                                 f"file://{page}"], out)
                except TimeoutError as e:
                    print(f"  fig {n}: {e}", file=sys.stderr)
                    continue
                ok = out.exists() and out.stat().st_size > 0
                print(f"  fig {n}: {out.name} {WIDTH}x{height}@{SCALE}x "
                      f"{'OK' if ok else 'FAILED'}")
                total += ok
    print(f"done: {total} images -> {OUT}")


if __name__ == "__main__":
    main()
