#!/usr/bin/env python3
"""Check traceability metadata for hidden contract regression tests."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path


CLASS_RE = re.compile(r"^class (C(?P<contract_id>\d{4})[A-Za-z0-9]+ContractTest)\b", re.MULTILINE)
CONTRACT_TAG_RE = re.compile(r'@Tag\("C-(\d{4})"\)')


def verify(test_root: Path) -> list[str]:
    errors = []
    paths = sorted(test_root.rglob("*.java"))
    if not paths:
        return [f"no contract regression tests found under {test_root}"]

    owners = {}
    for path in paths:
        text = path.read_text(encoding="utf-8")
        matches = list(CLASS_RE.finditer(text))
        relative = path.relative_to(test_root)
        if len(matches) != 1:
            errors.append(f"{relative}: expected one Cnnnn<Subject>ContractTest class")
            continue
        match = matches[0]
        class_name = match.group(1)
        if path.name != f"{class_name}.java":
            errors.append(f"{relative}: file name must match {class_name}")
        contract_id = match.group("contract_id")
        expected_tag = f'@Tag("C-{contract_id}")'
        lines = text.splitlines()
        class_line = text[: match.start()].count("\n")
        previous = class_line - 1
        while previous >= 0 and not lines[previous].strip():
            previous -= 1
        if previous < 0 or lines[previous].strip() != expected_tag:
            errors.append(f"{relative}: expected {expected_tag} immediately above the class")
        tags = CONTRACT_TAG_RE.findall(text)
        if tags != [contract_id]:
            errors.append(f"{relative}: expected exactly one contract tag C-{contract_id}")
        if contract_id in owners:
            errors.append(f"{relative}: C-{contract_id} is already used by {owners[contract_id]}")
        else:
            owners[contract_id] = relative
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--test-root", type=Path)
    args = parser.parse_args()
    test_root = args.test_root
    if test_root is None:
        test_root = Path(__file__).resolve().parents[1] / "verification/contract-tests/src/test/java"
    errors = verify(test_root.resolve())
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print(f"OK: hidden contract regression metadata under {test_root.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
