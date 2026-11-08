#!/usr/bin/env python3
"""Export only the LLM-visible portion of a historical repair sample."""

from __future__ import annotations

import argparse
import json
import zipfile
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("sample_id")
    parser.add_argument("-o", "--output", type=Path, required=True)
    args = parser.parse_args()

    repo_root = Path(__file__).resolve().parents[2]
    sample = repo_root / "historical-eval" / "samples" / args.sample_id
    manifest = json.loads((sample / "manifest.json").read_text(encoding="utf-8"))
    prompt = sample / "prompt"
    if not prompt.is_dir():
        parser.error(f"sample has no prompt directory: {args.sample_id}")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("sample.json", json.dumps({"id": manifest["id"], "categories": manifest["category"]}, indent=2) + "\n")
        for path in sorted(prompt.rglob("*")):
            if path.is_file():
                archive.write(path, path.relative_to(prompt).as_posix())

    names = set(zipfile.ZipFile(args.output).namelist())
    forbidden = [name for name in names if "oracle" in name or "hidden" in name or "fixed.patch" in name]
    if forbidden:
        raise SystemExit(f"prompt export leaked evaluator files: {forbidden}")
    print(json.dumps({"sample": manifest["id"], "files": sorted(names), "output": str(args.output)}))


if __name__ == "__main__":
    main()
