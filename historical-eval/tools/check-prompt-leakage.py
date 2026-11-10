#!/usr/bin/env python3
"""Validate that a model workspace contains only the allowed blind-eval inputs."""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path


def load_runner():
    path = Path(__file__).with_name("run-repair-loop.py")
    spec = importlib.util.spec_from_file_location("repair_runner", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("workspace", type=Path)
    parser.add_argument("--allowed", action="append", default=[])
    parser.add_argument("-o", "--output", type=Path)
    args = parser.parse_args()
    runner = load_runner()
    originals = {path: "" for path in args.allowed}
    result = runner.validate_model_workspace(args.workspace.resolve(), originals)
    result["workspace"] = str(args.workspace.resolve())
    payload = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(payload, encoding="utf-8")
    print(payload, end="")
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
