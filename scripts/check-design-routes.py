#!/usr/bin/env python3
"""Fail when design/AGENTS.md's route table drifts from the live documents.

Two directions:
- every relative Markdown link in design/AGENTS.md must resolve to an existing file;
- every git-tracked top-level design/*.md (live documents; AGENTS.md itself is
  excluded) must appear as a link target in the route table.

Untracked files are in-flight proposals and exempt by policy.
"""

from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
ROUTE_DOC = ROOT / "design" / "AGENTS.md"
LINK_RE = re.compile(r"\[([^\]]*)\]\(([^)\s]+)(?:\s+['\"][^)]*['\"])?\)")


def route_targets() -> dict[PurePosixPath, str]:
    """Map of link targets (relative to design/) to their raw form."""
    targets = {}
    for raw_target in LINK_RE.findall(ROUTE_DOC.read_text(encoding="utf-8")):
        raw = unquote(raw_target[1])
        parsed = urlparse(raw)
        if parsed.scheme or parsed.netloc or raw.startswith("#"):
            continue
        targets[PurePosixPath(parsed.path)] = raw
    return targets


def tracked_live_documents() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "design/*.md"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    documents = []
    for relative in result.stdout.splitlines():
        path = PurePosixPath(relative)
        # git pathspec * also matches /, so keep top-level documents and exclude AGENTS.md here
        if len(path.parts) == 2 and path.name != "AGENTS.md":
            documents.append(path.name)
    return sorted(documents)


def main() -> int:
    targets = route_targets()
    errors = []
    for target, raw in sorted(targets.items()):
        if not (ROUTE_DOC.parent / Path(*target.parts)).exists():
            errors.append(f"design/AGENTS.md: missing target: {raw}")
    for name in tracked_live_documents():
        if PurePosixPath(name) not in targets:
            errors.append(f"design/{name}: tracked live document missing from the route table")
    if errors:
        print("Design route table drift detected:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Design route table: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
