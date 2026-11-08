#!/usr/bin/env python3
"""Group candidate fixes that carry the same production diff across branches."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from collections import defaultdict
from pathlib import Path


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def patch_key(row: dict[str, object]) -> str:
    paths = [str(path) for path in row["production_files"]]
    patch = git("diff", "--binary", str(row["parent"]), str(row["commit"]), "--", *paths)
    return hashlib.sha256(patch).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("catalog", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    groups: dict[str, list[dict[str, object]]] = defaultdict(list)
    for line in args.catalog.read_text(encoding="utf-8").splitlines():
        row = json.loads(line)
        if row["disposition"] != "candidate" or not row["has_regression_test"]:
            continue
        groups[patch_key(row)].append(row)

    records = []
    for key, members in sorted(groups.items(), key=lambda item: item[1][0]["commit"]):
        representative = members[0]
        records.append(
            {
                "group": key[:16],
                "patch_sha256": key,
                "representative": representative["commit"],
                "subject": representative["subject"],
                "production_files": representative["production_files"],
                "member_count": len(members),
                "members": [member["commit"] for member in members],
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    print(json.dumps({"candidate_commits": sum(len(item["members"]) for item in records), "unique_diffs": len(records)}, sort_keys=True))


if __name__ == "__main__":
    main()
