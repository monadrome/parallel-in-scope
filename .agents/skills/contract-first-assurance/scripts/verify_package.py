#!/usr/bin/env python3
"""Verify the local contract-first-assurance package without third-party dependencies."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import List, Optional


VERSION_RE = re.compile(r'^version:\s*["\']?([^"\']+)["\']?\s*$')
COMPONENT_RE = re.compile(r"^\s+- path:\s+([^\s#]+)\s*$")


def frontmatter_version(path: Path) -> Optional[str]:
    text = path.read_text(encoding="utf-8")
    if not text.startswith("---\n"):
        return None
    for line in text.splitlines()[1:]:
        if line == "---":
            break
        match = VERSION_RE.match(line)
        if match:
            return match.group(1)
    return None


def package_version(path: Path) -> Optional[str]:
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith("  version:"):
            match = VERSION_RE.match(line.strip())
            return match.group(1) if match else None
    return None


def component_paths(path: Path) -> List[str]:
    paths = []
    in_components = False
    for line in path.read_text(encoding="utf-8").splitlines():
        if line == "components:":
            in_components = True
            continue
        if in_components and line and not line.startswith(" "):
            in_components = False
        if not in_components:
            continue
        match = COMPONENT_RE.match(line)
        if match:
            paths.append(match.group(1))
    return paths


def verify(root: Path) -> List[str]:
    errors = []
    manifest = root / "package.yaml"
    entrypoint = root / "SKILL.md"
    if not manifest.is_file():
        return [f"missing manifest: {manifest}"]
    if not entrypoint.is_file():
        errors.append(f"missing entrypoint: {entrypoint}")

    version = package_version(manifest)
    if not version:
        errors.append("manifest has no package version")

    for component in component_paths(manifest):
        component_root = root / component
        component_skill = component_root / "SKILL.md"
        if not component_skill.is_file():
            errors.append(f"missing component entrypoint: {component_skill}")
            continue
        if version and frontmatter_version(component_skill) != version:
            errors.append(
                f"version mismatch: {component_skill} != package {version}"
            )

    if entrypoint.is_file():
        text = entrypoint.read_text(encoding="utf-8")
        for marker in ("${SKILL_ROOT}", "${RUN_DIR}"):
            if marker not in text:
                errors.append(f"entrypoint does not define portable marker: {marker}")
        if ".agents/skills/contract-" in text:
            errors.append("entrypoint contains a fixed project skill path")

    for reference in ("references/invocation-modes.md", "references/host-adapter.md"):
        if not (root / reference).is_file():
            errors.append(f"missing package reference: {root / reference}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = verify(args.root.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: {args.root.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
