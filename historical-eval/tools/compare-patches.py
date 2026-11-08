#!/usr/bin/env python3
"""Run the same hidden verifier against multiple model patches."""

from __future__ import annotations

import argparse
import json
import subprocess
import time
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sample", default="BUG-0001")
    parser.add_argument(
        "--candidate",
        action="append",
        required=True,
        metavar="NAME=PATCH",
        help="model label and patch path; repeat for each model",
    )
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    verifier = Path(__file__).with_name("verify-sample.sh")
    results = []
    for item in args.candidate:
        name, separator, patch = item.partition("=")
        if not separator or not name or not patch:
            parser.error(f"invalid candidate {item!r}; expected NAME=PATCH")
        start = time.monotonic()
        completed = subprocess.run(
            [str(verifier), args.sample, patch],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            text=True,
            check=False,
        )
        results.append(
            {
                "model": name,
                "patch": str(Path(patch)),
                "passed": completed.returncode == 0,
                "returncode": completed.returncode,
                "duration_seconds": round(time.monotonic() - start, 3),
            }
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
