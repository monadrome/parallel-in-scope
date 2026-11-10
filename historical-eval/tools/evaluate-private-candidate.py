#!/usr/bin/env python3
"""Stable private-evaluator entrypoint with a coarse public result schema."""
from __future__ import annotations
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rank", type=int, required=True)
    parser.add_argument("--patch", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", required=True)
    args = parser.parse_args()
    evaluator = os.environ.get("HISTORICAL_EVAL_PRIVATE_EVALUATOR")
    if not evaluator:
        private_root = os.environ.get("HISTORICAL_EVAL_PRIVATE_DIR")
        if private_root:
            candidate = Path(private_root) / "evaluate_rescue.py"
            if candidate.exists():
                evaluator = str(candidate)
    if not evaluator:
        result = {"schema": "historical-private-evaluator/v1", "rank": args.rank, "status": "evaluator-unconfigured"}
        args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))
        return 2
    private_result = args.output.with_suffix(args.output.suffix + ".private.json")
    command = [sys.executable, evaluator, str(args.rank), str(args.patch), str(private_result), args.model]
    completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
    status = "evaluator-error"
    if private_result.exists():
        try:
            data = json.loads(private_result.read_text(encoding="utf-8"))
            hidden_rc = data.get("after", {}).get("hidden", {}).get("returncode")
            status = "rescue-pass" if hidden_rc == 0 else "rescue-fail"
        except json.JSONDecodeError:
            status = "evaluator-error"
    result = {"schema": "historical-private-evaluator/v1", "rank": args.rank, "model": args.model, "status": status, "returncode": completed.returncode}
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False))
    return 0 if status == "rescue-pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
