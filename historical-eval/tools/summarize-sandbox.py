#!/usr/bin/env python3
"""Join difficulty ranking with sandbox outcomes for the final review report."""
from __future__ import annotations
import argparse
import json
from collections import Counter
from pathlib import Path

def load(path: Path):
    return {json.loads(line)["group"]: json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("results", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()
    ranking = load(args.ranking)
    results = load(args.results)
    rows = []
    for group, rank in ranking.items():
        result = results.get(group)
        if result is None:
            status = "not-run"
            result = {"status": status}
        else:
            status = result["status"]
        rows.append({
            "rank": rank["rank"],
            "group": group,
            "representative": rank["representative"],
            "subject": rank["subject"],
            "consensus": rank["consensus"],
            "votes": rank["votes"],
            "priority": rank["priority"],
            "status": status,
            "before_returncode": result.get("before_returncode"),
            "after_returncode": result.get("after_returncode"),
            "before_seconds": result.get("before_seconds"),
            "after_seconds": result.get("after_seconds"),
            "test_files": result.get("test_files", []),
        })
    rows.sort(key=lambda row: row["rank"])
    args.output.parent.mkdir(parents=True, exist_ok=True)
    json_path = args.output.with_suffix(".jsonl")
    with json_path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    counts = Counter(row["status"] for row in rows)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write("# Difficulty ranking and sandbox outcomes\n\n")
        handle.write(f"Total groups: **{len(rows)}**.\n\n")
        handle.write("| Rank | Group | Difficulty | Votes | Priority | Sandbox result | Representative | Subject |\n|---:|---|---|---|---|---|---|---|\n")
        for row in rows:
            subject = row["subject"].replace("|", "\\|")
            handle.write(f"| {row['rank']} | `{row['group']}` | **{row['consensus']}** | {'/'.join(row['votes'].values())} | {row['priority']} | `{row['status']}` | `{row['representative'][:12]}` | {subject} |\n")
        handle.write("\n## Sandbox result counts\n\n")
        for status in ("verified", "baseline-pass", "fixed-fail", "no-test-files", "sandbox-error", "not-run"):
            handle.write(f"- `{status}`: {counts[status]}\n")
        handle.write("\n`verified` means the historical regression tests fail on the parent snapshot and pass on the fixed snapshot. `baseline-pass` means the tests do not distinguish the bug. `fixed-fail` means the fixed snapshot could not pass the selected tests or compile.\n")
    print(json.dumps({"groups": len(rows), "statuses": counts, "markdown": str(args.output), "jsonl": str(json_path)}, sort_keys=True))

if __name__ == "__main__":
    main()
