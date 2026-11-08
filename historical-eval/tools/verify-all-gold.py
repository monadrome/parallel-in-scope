#!/usr/bin/env python3
"""Verify every checked-in historical gold sample with its evaluator patch."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    verifier = repo_root / "historical-eval" / "tools" / "verify-sample.sh"
    results = []
    for manifest_path in sorted((repo_root / "historical-eval" / "samples").glob("*/manifest.json")):
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        sample_dir = manifest_path.parent
        patch = sample_dir / "oracle" / "fixed.patch"
        start = time.monotonic()
        completed = subprocess.run(
            [str(verifier), manifest["id"], str(patch)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        results.append(
            {
                "sample": manifest["id"],
                "category": manifest["category"],
                "passed": completed.returncode == 0,
                "returncode": completed.returncode,
                "duration_seconds": round(time.monotonic() - start, 3),
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(results, indent=2))
    if not all(result["passed"] for result in results):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
