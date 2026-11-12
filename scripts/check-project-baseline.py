#!/usr/bin/env python3
"""Check that live review gates use the development line declared by the project."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
BASELINE = ROOT / ".agents" / "project-baseline.json"
AGENTS = ROOT / "AGENTS.md"
CONFIG = ROOT / "refactoring-review" / "config.json"
JAVA_GATE = ROOT / "scripts" / "check-java-conventions.py"
COMPLEXITY_SKILL = ROOT / ".agents" / "skills" / "complexity-review-eval" / "SKILL.md"
DOC = ROOT / "verification" / "java-conventions.md"


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {path}: {error}") from error


def main() -> int:
    errors = []
    try:
        baseline = read_json(BASELINE)
    except ValueError as error:
        print(f"ERROR: {error}")
        return 1

    development_line = baseline.get("developmentLine")
    upstream_ref = baseline.get("upstreamRef")
    if not isinstance(development_line, str) or not isinstance(upstream_ref, str):
        errors.append("baseline must define string developmentLine and upstreamRef")
    elif upstream_ref != f"origin/{development_line}":
        errors.append(f"upstreamRef {upstream_ref!r} does not match developmentLine {development_line!r}")

    agents_text = AGENTS.read_text(encoding="utf-8")
    declared = re.search(r"All new work targets `([^`]+)`", agents_text)
    if not declared:
        errors.append("AGENTS.md does not declare the current development line")
    elif declared.group(1) != development_line:
        errors.append(f"AGENTS.md declares {declared.group(1)!r}, expected {development_line!r}")

    try:
        config_base = read_json(CONFIG).get("defaultBase")
    except ValueError as error:
        errors.append(str(error))
    else:
        if config_base != upstream_ref:
            errors.append(f"refactoring-review/config.json defaultBase is {config_base!r}, expected {upstream_ref!r}")

    java_text = JAVA_GATE.read_text(encoding="utf-8")
    if f'DEFAULT_UPSTREAM = "{upstream_ref}"' not in java_text:
        errors.append(f"scripts/check-java-conventions.py does not use {upstream_ref}")

    complexity_text = COMPLEXITY_SKILL.read_text(encoding="utf-8")
    if upstream_ref not in complexity_text:
        errors.append(f"complexity-review-eval skill does not document {upstream_ref}")

    doc_text = DOC.read_text(encoding="utf-8")
    if "check-java-conventions.py" not in doc_text:
        errors.append("verification/java-conventions.md does not document the Java convention gate")
    live_files = (AGENTS, CONFIG, JAVA_GATE, COMPLEXITY_SKILL, DOC)
    live_text = "\n".join(path.read_text(encoding="utf-8") for path in live_files)
    if "origin/dev/v0.4.0" in live_text:
        errors.append("a live baseline file still contains origin/dev/v0.4.0")

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: {upstream_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
