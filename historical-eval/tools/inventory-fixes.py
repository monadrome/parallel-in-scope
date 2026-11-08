#!/usr/bin/env python3
"""Inventory historical fix commits for the blind-repair dataset."""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from pathlib import Path

DOC_PREFIXES = ("docs/", "design/", "adr/", "README", "CHANGELOG")
TEST_PREFIXES = ("src/test/", "core/src/test/", "blocking-queues/src/test/", "demo/src/test/")
TOOL_PREFIXES = ("scripts/", "verification/", ".github/")
SOURCE_PREFIXES = (
    "src/main/",
    "core/src/main/",
    "blocking-queues/src/main/",
    "demo/src/main/",
    "integrations/src/main/",
)


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True)


def classify(path: str) -> str:
    if path.startswith(DOC_PREFIXES):
        return "documentation"
    if path.startswith(TEST_PREFIXES) or path.endswith("Test.java"):
        return "test"
    if path.startswith(TOOL_PREFIXES):
        return "tooling"
    if path.startswith(SOURCE_PREFIXES):
        return "production"
    return "other"


def commits() -> list[tuple[str, str, str]]:
    rows = []
    output = git("log", "--all", "--no-merges", "--format=%H%x09%P%x09%s")
    for line in output.splitlines():
        commit, parents, subject = line.split("\t", 2)
        if not re.search(r"(?:^|[\s(:!])fix(?:$|[\s:!)]|\()", subject, re.IGNORECASE):
            continue
        parent = parents.split()[0] if parents else ""
        if parent:
            rows.append((commit, parent, subject))
    return rows


def changed_files(parent: str, commit: str) -> list[str]:
    output = git("diff", "--name-only", parent, commit, "--")
    return sorted(path for path in output.splitlines() if path)


def record(commit: str, parent: str, subject: str) -> dict[str, object]:
    files = changed_files(parent, commit)
    groups = {kind: [] for kind in ("production", "test", "tooling", "documentation", "other")}
    for path in files:
        groups[classify(path)].append(path)
    production = groups["production"]
    tests = groups["test"]
    tooling = groups["tooling"]
    docs = groups["documentation"]
    if production:
        disposition = "candidate"
    elif tooling:
        disposition = "tooling-only"
    elif docs and len(docs) == len(files):
        disposition = "documentation-only"
    elif tests and len(tests) == len(files):
        disposition = "test-only"
    else:
        disposition = "other"
    return {
        "commit": commit,
        "parent": parent,
        "subject": subject,
        "files": files,
        "production_files": production,
        "test_files": tests,
        "tooling_files": tooling,
        "documentation_files": docs,
        "disposition": disposition,
        "has_regression_test": bool(production and tests),
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()
    records = [record(*row) for row in commits()]
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for item in records:
            handle.write(json.dumps(item, ensure_ascii=False, sort_keys=True) + "\n")
    counts: dict[str, int] = {}
    for item in records:
        disposition = str(item["disposition"])
        counts[disposition] = counts.get(disposition, 0) + 1
    print(json.dumps({"records": len(records), "by_disposition": counts}, sort_keys=True))


if __name__ == "__main__":
    main()
