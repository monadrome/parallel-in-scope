#!/usr/bin/env python3
"""Prepare a reviewable sample skeleton from one historical fix commit."""

from __future__ import annotations

import argparse
import json
import subprocess
from pathlib import Path


def git(*args: str) -> bytes:
    return subprocess.check_output(["git", *args])


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sample_id")
    parser.add_argument("fix_commit")
    parser.add_argument("--source", action="append", required=True, help="production path changed by the fix")
    parser.add_argument("--test-class", required=True)
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    parent = git("rev-parse", f"{args.fix_commit}^").decode().strip()
    output = args.output
    if output.exists() and any(output.iterdir()):
        parser.error(f"output directory is not empty: {output}")
    output.mkdir(parents=True, exist_ok=True)

    source_root = output / "prompt"
    for path in args.source:
        destination = source_root / path
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_bytes(git("show", f"{parent}:{path}"))

    manifest = {
        "id": args.sample_id,
        "buggy_commit": parent,
        "fixed_commit": args.fix_commit,
        "production_files": args.source,
        "test_class": args.test_class,
        "status": "needs-curation",
        "oracle_policy": "fixed patch and regression tests are derived inside the evaluator sandbox",
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    (output / "prompt" / "task.md").write_text(
        "请补写中性任务描述，并确认其不包含提交信息、修复答案或隐藏测试线索。\n",
        encoding="utf-8",
    )
    print(json.dumps({"sample": args.sample_id, "parent": parent, "output": str(output)}))


if __name__ == "__main__":
    main()
