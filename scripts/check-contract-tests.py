#!/usr/bin/env python3
"""Check traceability metadata for hidden contract regression tests."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


CLASS_RE = re.compile(r"^class (C(?P<contract_id>\d{4})[A-Za-z0-9]+ContractTest)\b", re.MULTILINE)
CONTRACT_TAG_RE = re.compile(r'@Tag\("C-(\d{4})"\)')


def verify_execution(reports: Path, classes: dict[str, Path]) -> list[str]:
    errors = []
    report_paths = sorted(reports.rglob("TEST-*.xml"))
    if not report_paths:
        return [f"no Surefire XML reports found under {reports}"]

    testcases = {class_name: [] for class_name in classes}
    for report_path in report_paths:
        try:
            root = ET.parse(report_path).getroot()
        except (OSError, ET.ParseError) as error:
            errors.append(f"cannot parse Surefire report {report_path}: {error}")
            continue
        for testcase in root.iter("testcase"):
            class_name = testcase.get("classname", "").rsplit(".", 1)[-1]
            if class_name in testcases:
                testcases[class_name].append(testcase)

    for class_name, relative in classes.items():
        matches = testcases[class_name]
        if not matches:
            errors.append(f"{relative}: no executed testcase for {class_name}")
        for testcase in matches:
            method_name = testcase.get("name", "<unnamed>")
            for outcome in ("skipped", "failure", "error"):
                if testcase.find(outcome) is not None:
                    errors.append(f"{relative}: testcase {method_name} has {outcome}")
    return errors


def verify(test_root: Path, reports: Path | None = None) -> list[str]:
    errors = []
    paths = sorted(test_root.rglob("*.java"))
    if not paths:
        return [f"no contract regression tests found under {test_root}"]

    owners = {}
    classes = {}
    for path in paths:
        text = path.read_text(encoding="utf-8")
        matches = list(CLASS_RE.finditer(text))
        relative = path.relative_to(test_root)
        if len(matches) != 1:
            errors.append(f"{relative}: expected one Cnnnn<Subject>ContractTest class")
            continue
        match = matches[0]
        class_name = match.group(1)
        classes[class_name] = relative
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
    if reports is not None:
        errors.extend(verify_execution(reports, classes))
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--test-root", type=Path)
    parser.add_argument("--reports", type=Path)
    args = parser.parse_args()
    test_root = args.test_root
    if test_root is None:
        test_root = Path(__file__).resolve().parents[1] / "verification/contract-tests/src/test/java"
    reports = args.reports.resolve() if args.reports is not None else None
    errors = verify(test_root.resolve(), reports)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    scope = "metadata and execution evidence" if reports is not None else "metadata"
    print(f"OK: hidden contract regression {scope} under {test_root.resolve()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
