#!/usr/bin/env python3
"""Check that live documentation is self-contained within the repository.

Two reference styles are rejected because they silently rot:

- a commit hash used to retrieve content (a ``git show``/``git log`` command);
- a line number appended to a file path.

Historical material belongs in a tracked archive directory instead. See the
"Documentation References" section of the root AGENTS.md.
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

SCAN_FILES = {
    "AGENTS.md",
    "BACKLOG.md",
    "CHANGELOG.md",
    "CONTRIBUTING.md",
    "README.md",
    "README.zh-CN.md",
    "todo.md",
}
SCAN_PREFIXES = (
    ".github/",
    "adr/",
    "design/",
    "docs/",
    "mkdocs/",
)
EXEMPT_PREFIXES = (
    "design/analysis/",
    "design/archive/",
    "design/history/",
    "adr/archive/",
)
EXEMPT_FILES = {
    "BACKLOG.md",
    "CHANGELOG.md",
    "todo.md",
}

HASH_RETRIEVAL = re.compile(r"\bgit\s+(?:show|log)\b")
LINE_SUFFIX = re.compile(
    r"(?<![A-Za-z0-9_./-])[A-Za-z0-9_./-]+\.(?:md|java|py|ya?ml|xml|sh|html|kt|ts)"
    r":\d+(?:[-,]\d+)*"
)
HISTORY_RETRIEVAL = re.compile(
    r"git\s*历史[^。\n]{0,12}(?:取回|取到)"
    r"|recoverable\s+(?:with|from)\s+git\s+history"
    r"|git\s+history\s+holds"
)


def is_scanned(relative: str) -> bool:
    if relative in EXEMPT_FILES or relative.startswith(EXEMPT_PREFIXES):
        return False
    return relative in SCAN_FILES or relative.startswith(SCAN_PREFIXES)


def markdown_files(root: Path) -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "*.md"],
        cwd=root,
        check=True,
        capture_output=True,
        text=True,
    )
    return sorted(path for path in result.stdout.splitlines() if path and is_scanned(path))


def violations(root: Path) -> list[str]:
    found: list[str] = []
    for relative in markdown_files(root):
        text = (root / relative).read_text(encoding="utf-8")
        for number, line in enumerate(text.splitlines(), 1):
            if HASH_RETRIEVAL.search(line):
                found.append(f"{relative}:{number}: hash-based retrieval: {line.strip()}")
            if LINE_SUFFIX.search(line):
                found.append(f"{relative}:{number}: line-number reference: {line.strip()}")
            if HISTORY_RETRIEVAL.search(line):
                found.append(f"{relative}:{number}: git-history retrieval: {line.strip()}")
    return found


def main() -> int:
    found = violations(ROOT)
    if found:
        print("Documentation references failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in found), file=sys.stderr)
        return 1
    print("Documentation references: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
