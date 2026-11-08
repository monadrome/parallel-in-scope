#!/usr/bin/env python3
"""Create a review report for every deduplicated historical fix group."""

from __future__ import annotations

import argparse
import json
import re
from collections import Counter
from pathlib import Path

RULES = [
    ("queue", r"\bqueue\b|purger|drain|capacity"),
    ("batch-submission", r"batch|submit|submission|sliding|placeholder|awaitbodycompletion"),
    ("deadline", r"deadline|timeout|duration|timing|clock"),
    ("interruption", r"interrupt|quiescence"),
    ("cancellation", r"cancel|token"),
    ("executor", r"executor|pool|deadlock|starvation|busy wait|handoff"),
    ("task-group", r"group|member|combine|tasktype"),
    ("observation-graph", r"observation|graph|report|snapshot|scope"),
    ("context-propagation", r"context|borrowed|ttl|parent"),
    ("listener-cleanup", r"listener|close|cleanup|release|mirror|lifecycle"),
    ("api-build", r"api|annotation|nullable|error prone|diagnostic|options"),
]

def category(subject: str, files: list[str]) -> str:
    haystack = f"{subject} {' '.join(files)}".lower()
    for name, pattern in RULES:
        if re.search(pattern, haystack):
            return name
    return "other"


def difficulty(subject: str, files: list[str], member_count: int) -> str:
    haystack = subject.lower()
    if member_count >= 4 or len(files) >= 5:
        return "hard"
    if any(word in haystack for word in ("race", "atomic", "deadlock", "starvation", "lifecycle", "interrupt")):
        return "hard"
    if len(files) >= 2 or member_count >= 2:
        return "medium"
    return "easy"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("groups", type=Path)
    parser.add_argument("samples", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    curated: dict[str, str] = {}
    for manifest_path in sorted(args.samples.glob("*/manifest.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        curated[manifest["fixed_commit"]] = manifest["id"]

    rows = []
    for line in args.groups.read_text(encoding="utf-8").splitlines():
        group = json.loads(line)
        sample_id = next((curated[commit] for commit in group["members"] if commit in curated), None)
        row = {
            "group": group["group"],
            "representative": group["representative"],
            "subject": group["subject"],
            "category": category(group["subject"], group["production_files"]),
            "difficulty": difficulty(group["subject"], group["production_files"], group["member_count"]),
            "member_count": group["member_count"],
            "production_files": group["production_files"],
            "curation_status": "gold" if sample_id else "needs-curation",
            "sample_id": sample_id,
            "members": group["members"],
        }
        rows.append(row)

    summary = Counter((row["category"], row["curation_status"]) for row in rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    json_path = args.output.with_suffix(".jsonl")
    with json_path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")

    with args.output.open("w", encoding="utf-8") as handle:
        handle.write("# Deduplicated historical fix group inventory\n\n")
        handle.write(f"Total groups: **{len(rows)}**. Curated gold samples: **{sum(row['curation_status'] == 'gold' for row in rows)}**.\n\n")
        handle.write("| # | Group | Category | Difficulty | Representative | Members | Status | Subject | Production files |\n")
        handle.write("|---:|---|---|---|---|---:|---|---|---|\n")
        for index, row in enumerate(rows, 1):
            subject = row["subject"].replace("|", "\\|")
            files = "<br>".join(row["production_files"]).replace("|", "\\|")
            handle.write(
                f"| {index} | `{row['group']}` | {row['category']} | {row['difficulty']} | "
                f"`{row['representative'][:8]}` | {row['member_count']} | {row['curation_status']} | "
                f"{subject} | {files} |\n"
            )
        handle.write("\n## Category counts\n\n")
        for (kind, status), count in sorted(summary.items()):
            handle.write(f"- `{kind}` / `{status}`: {count}\n")

    print(json.dumps({"groups": len(rows), "gold": sum(row["curation_status"] == "gold" for row in rows), "jsonl": str(json_path), "markdown": str(args.output)}, sort_keys=True))


if __name__ == "__main__":
    main()
