#!/usr/bin/env python3
"""Fail when an English document contains Han characters.

Markdown under an English location must be English. An HTML page under an English location must be
English too, unless its root element declares another language (``<html lang="zh-CN">``): a page
that states its own language honestly is a deliberate translation, not a stray one, and the
navigation that links it must say so.
"""

from pathlib import Path
import re
import sys


ROOT = Path(__file__).resolve().parents[1]
HAN = re.compile(r"[㐀-鿿]")
HTML_LANG = re.compile(r"<html\b[^>]*\blang\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE)


def is_english_location(root: Path, path: Path) -> bool:
    relative = path.relative_to(root)
    parts = tuple(part.lower() for part in relative.parts)
    name = path.name.lower()
    return "en" in parts or name.endswith(".en.md") or name.endswith("_en.md")


def declares_other_language(text: str) -> bool:
    match = HTML_LANG.search(text)
    return match is not None and not match.group(1).lower().startswith("en")


def han_lines(root: Path, path: Path, text: str) -> list:
    return [
        f"{path.relative_to(root)}:{line_number}:{line}"
        for line_number, line in enumerate(text.splitlines(), 1)
        if HAN.search(line)
    ]


def violations(root: Path) -> list:
    found = []
    for document in sorted(root.rglob("*.md")):
        if is_english_location(root, document):
            found.extend(han_lines(root, document, document.read_text(encoding="utf-8")))
    for page in sorted(root.rglob("*.html")):
        if "target" in page.relative_to(root).parts or not is_english_location(root, page):
            continue
        text = page.read_text(encoding="utf-8")
        if not declares_other_language(text):
            found.extend(han_lines(root, page, text))
    return found


def main() -> int:
    found = violations(ROOT)
    if found:
        print("English documentation contains Han characters:", file=sys.stderr)
        print("\n".join(found), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
