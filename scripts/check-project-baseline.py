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
ADAPTER = ROOT / ".agents" / "skills" / "contract-first-assurance" / "adapters" / "vformation.yaml"
DEVELOPMENT_REF = re.compile(r"origin/dev/v\d+\.\d+\.\d+")
ADAPTER_BASE = re.compile(r'''(?m)^base_ref:\s*(?:(['"])(.*?)\1|([^#\s]+))(?:\s+#.*)?\s*$''')


def read_json(path: Path) -> dict:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ValueError(f"cannot read {path}: {error}") from error


def validate_baseline(baseline: dict, errors: list[str]) -> tuple[object, object]:
    development_line = baseline.get("developmentLine")
    upstream_ref = baseline.get("upstreamRef")
    if not isinstance(development_line, str) or not isinstance(upstream_ref, str):
        errors.append("baseline must define string developmentLine and upstreamRef")
    elif upstream_ref != f"origin/{development_line}":
        errors.append(f"upstreamRef {upstream_ref!r} does not match developmentLine {development_line!r}")
    return development_line, upstream_ref


def validate_agents(development_line: object, errors: list[str]) -> None:
    agents_text = AGENTS.read_text(encoding="utf-8")
    declared = re.search(r"All new work targets `([^`]+)`", agents_text)
    if not declared:
        errors.append("AGENTS.md does not declare the current development line")
    elif declared.group(1) != development_line:
        errors.append(f"AGENTS.md declares {declared.group(1)!r}, expected {development_line!r}")


def validate_review_config(upstream_ref: object, errors: list[str]) -> None:
    try:
        config_base = read_json(CONFIG).get("defaultBase")
    except ValueError as error:
        errors.append(str(error))
    else:
        if config_base != upstream_ref:
            errors.append(f"refactoring-review/config.json defaultBase is {config_base!r}, expected {upstream_ref!r}")


def validate_script_and_docs(upstream_ref: object, errors: list[str]) -> None:
    java_text = JAVA_GATE.read_text(encoding="utf-8")
    if f'DEFAULT_UPSTREAM = "{upstream_ref}"' not in java_text:
        errors.append(f"scripts/check-java-conventions.py does not use {upstream_ref}")

    complexity_text = COMPLEXITY_SKILL.read_text(encoding="utf-8")
    if upstream_ref not in complexity_text:
        errors.append(f"complexity-review-eval skill does not document {upstream_ref}")

    doc_text = DOC.read_text(encoding="utf-8")
    if "check-java-conventions.py" not in doc_text:
        errors.append("verification/java-conventions.md does not document the Java convention gate")
    if upstream_ref not in doc_text:
        errors.append(f"verification/java-conventions.md does not document {upstream_ref}")

    adapter_text = ADAPTER.read_text(encoding="utf-8")
    adapter_base = ADAPTER_BASE.search(adapter_text)
    if not adapter_base:
        errors.append("contract-first-assurance adapter does not declare base_ref")
    else:
        declared_base = adapter_base.group(2) if adapter_base.group(1) else adapter_base.group(3)
        if declared_base != upstream_ref:
            errors.append(f"contract-first-assurance adapter declares {declared_base!r}, expected {upstream_ref!r}")


def validate_stale_references(upstream_ref: object, errors: list[str]) -> None:
    if not isinstance(upstream_ref, str):
        return
    live_files = (AGENTS, CONFIG, JAVA_GATE, COMPLEXITY_SKILL, DOC, ADAPTER)
    seen: set[tuple[Path, str]] = set()
    for path in live_files:
        text = path.read_text(encoding="utf-8")
        for found in DEVELOPMENT_REF.findall(text):
            if found != upstream_ref and (path, found) not in seen:
                seen.add((path, found))
                errors.append(f"{path.relative_to(ROOT)} contains stale development ref {found}")


def main() -> int:
    errors = []
    try:
        baseline = read_json(BASELINE)
    except ValueError as error:
        print(f"ERROR: {error}")
        return 1

    development_line, upstream_ref = validate_baseline(baseline, errors)
    validate_agents(development_line, errors)
    validate_review_config(upstream_ref, errors)
    validate_script_and_docs(upstream_ref, errors)
    validate_stale_references(upstream_ref, errors)

    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: {upstream_ref}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
