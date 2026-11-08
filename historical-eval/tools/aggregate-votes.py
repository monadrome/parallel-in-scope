#!/usr/bin/env python3
"""Aggregate three independent difficulty votes and sort groups hardest first."""
from __future__ import annotations
import argparse
import json
from collections import Counter
from pathlib import Path

LEVELS = {"easy": 1, "medium": 2, "hard": 3}
PRIORITY = {"low": 1, "medium": 2, "high": 3}

def read_votes(path: Path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        result[row["group"]] = row
    return result

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("complexity", type=Path)
    parser.add_argument("testability", type=Path)
    parser.add_argument("impact", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()
    votes = [read_votes(path) for path in (args.complexity, args.testability, args.impact)]
    groups = sorted(set(votes[0]) & set(votes[1]) & set(votes[2]))
    rows = []
    for group in groups:
        entries = [vote[group] for vote in votes]
        levels = [str(entries[0].get("vote")), str(entries[1].get("difficulty")), str(entries[2].get("difficulty"))]
        counts = Counter(levels)
        top = max(counts.values())
        tied = sorted(level for level, count in counts.items() if count == top)
        if top >= 2:
            consensus, method = tied[0], "majority"
        else:
            consensus, method = sorted(levels, key=lambda level: LEVELS[level])[1], "median-no-majority"
        rows.append({
            "group": group,
            "representative": entries[0]["representative"],
            "subject": entries[0]["subject"],
            "votes": {"complexity": levels[0], "testability": levels[1], "impact": levels[2]},
            "consensus": consensus,
            "consensus_method": method,
            "agreement": top,
            "priority": entries[2].get("priority", "medium"),
            "complexity_score": entries[0].get("score"),
            "member_count": entries[0].get("member_count"),
            "production_file_count": len(entries[0].get("production_files", [])),
            "reasons": {"complexity": entries[0].get("reason", ""), "testability": entries[1].get("reason", ""), "impact": entries[2].get("reason", "")},
        })
    rows.sort(key=lambda row: (-LEVELS[row["consensus"]], -row["agreement"], -PRIORITY.get(str(row["priority"]), 0), -(row["complexity_score"] or 0), row["group"]))
    for index, row in enumerate(rows, 1):
        row["rank"] = index
    args.output.parent.mkdir(parents=True, exist_ok=True)
    json_path = args.output.with_suffix(".jsonl")
    with json_path.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False, sort_keys=True) + "\n")
    counts = Counter(row["consensus"] for row in rows)
    with args.output.open("w", encoding="utf-8") as handle:
        handle.write("# Three-agent difficulty vote ranking\n\n")
        handle.write(f"Total groups: **{len(rows)}**. Sorted from hard to easy.\n\n")
        handle.write("| Rank | Group | Consensus | Votes (complexity/testability/impact) | Agreement | Priority | Representative | Subject |\n|---:|---|---|---|---:|---|---|---|\n")
        for row in rows:
            subject = row["subject"].replace("|", "\\|")
            handle.write(f"| {row['rank']} | `{row['group']}` | **{row['consensus']}** ({row['consensus_method']}) | {'/'.join(row['votes'].values())} | {row['agreement']}/3 | {row['priority']} | `{row['representative'][:12]}` | {subject} |\n")
        handle.write("\n## Consensus counts\n\n")
        for level in ("hard", "medium", "easy"):
            handle.write(f"- `{level}`: {counts[level]}\n")
        handle.write("\n`majority` means at least two votes agree. `median-no-majority` is used when all three differ.\n")
    print(json.dumps({"groups": len(rows), "consensus": counts, "markdown": str(args.output), "jsonl": str(json_path)}, sort_keys=True))

if __name__ == "__main__":
    main()
