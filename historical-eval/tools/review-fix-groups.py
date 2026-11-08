#!/usr/bin/env python3
"""Create the 103-group human review table from the deduplicated catalog."""

from __future__ import annotations

import argparse
import csv
import json
import re
from collections import Counter
from pathlib import Path

RULES = [
    ("deadline", ("deadline", "timeout", "duration", "quiescence")),
    ("cancellation", ("cancel", "cancellation", "purger")),
    ("interruption", ("interrupt", "interruption", "thread flag")),
    ("queue", ("queue", "capacity", "draining", "offer", "purge")),
    ("executor", ("executor", "pool", "handoff", "starvation", "deadlock", "combine")),
    ("batch/task-group", ("batch", "task group", "taskgroup", "member", "submission", "barrier")),
    ("graph/observation", ("graph", "observation", "diagnostic", "listener", "report")),
    ("context/propagation", ("context", "ttl", "scope")),
    ("api/contract", ("api", "contract", "name", "annotation", "options")),
]
NOISE = ("javadoc", "documentation", "annotation contracts", "error prone", "review", "verification", "ci ")


def category(subject: str, files: list[str]) -> str:
    text = (subject + " " + " ".join(files)).lower()
    for name, terms in RULES:
        if any(term in text for term in terms):
            return name
    return "other"


def disposition(subject: str) -> str:
    lower = subject.lower()
    if any(term in lower for term in NOISE):
        return "manual-check-noise"
    return "candidate"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("groups", type=Path)
    parser.add_argument("inventory", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    inventory = {}
    for line in args.inventory.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        inventory[row["commit"]] = row

    rows = []
    for index, line in enumerate(args.groups.read_text(encoding="utf-8").splitlines(), 1):
        group = json.loads(line)
        representative = group["representative"]
        source = inventory[representative]
        subject = group["subject"]
        status = "gold-verified" if representative in {
            "a5c2d823fad133907cefa30a357431623b609352",
            "661d2ebd2f23da42b51965b4bf16d9b6fb26b24d",
            "c0b0158bf4fcccaf4bf844495981a54ae3735618",
        } else "needs-human-review"
        rows.append(
            {
                "review_id": f"FIX-{index:03d}",
                "group": group["group"],
                "representative": representative,
                "subject": subject,
                "category": category(subject, group["production_files"]),
                "disposition": disposition(subject),
                "review_status": status,
                "member_count": group["member_count"],
                "production_file_count": len(group["production_files"]),
                "test_file_count": len(source["test_files"]),
                "production_files": ";".join(group["production_files"]),
                "test_files": ";".join(source["test_files"]),
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    print(json.dumps({"groups": len(rows), "categories": Counter(row["category"] for row in rows)}, sort_keys=True))


if __name__ == "__main__":
    main()
