#!/usr/bin/env python3
"""Verify that the required project skills are present and tracked in this worktree."""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / ".worktreeinclude.yaml"
PATH_RE = re.compile(r"^\s+- path:\s+([^\s#]+)\s*$")


def required_paths() -> list[str]:
    return [
        match.group(1)
        for line in MANIFEST.read_text(encoding="utf-8").splitlines()
        if (match := PATH_RE.match(line))
    ]


def tracked(path: str) -> bool:
    result = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "--", path],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    if result.returncode == 0:
        return True
    listing = subprocess.run(["git", "ls-files", "--", f"{path}/"], cwd=ROOT, capture_output=True, text=True)
    return listing.returncode == 0 and bool(listing.stdout.strip())


def main() -> int:
    errors = []
    if not MANIFEST.is_file():
        print(f"ERROR: missing {MANIFEST}")
        return 1

    for relative in required_paths():
        path = ROOT / relative
        if not path.exists():
            errors.append(f"missing required skill: {relative}")
            continue
        if path.is_symlink():
            errors.append(f"required skill must not be a symlink: {relative}")
        if path.is_dir() and not (path / "SKILL.md").is_file():
            errors.append(f"skill directory has no SKILL.md: {relative}")
        if not tracked(relative):
            errors.append(f"required skill is not tracked: {relative}")

    package_verifier = ROOT / ".agents" / "skills" / "contract-first-assurance" / "scripts" / "verify_package.py"
    if package_verifier.is_file():
        result = subprocess.run([sys.executable, str(package_verifier)], cwd=ROOT, capture_output=True, text=True)
        if result.returncode != 0:
            errors.append("contract-first-assurance package verification failed: " + result.stdout.strip())

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: {len(required_paths())} tracked project skill directories")
    return 0


if __name__ == "__main__":
    sys.exit(main())
