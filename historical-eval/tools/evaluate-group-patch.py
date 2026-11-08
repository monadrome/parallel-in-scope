#!/usr/bin/env python3
"""Evaluate one model patch against a historical group's regression tests."""
from __future__ import annotations
import argparse
import importlib.util
import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def load_runner(path: Path):
    spec = importlib.util.spec_from_file_location("sandbox_runner", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("group")
    parser.add_argument("patch", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--timeout", type=int, default=180)
    args = parser.parse_args()
    patch_path = args.patch.resolve()
    runner = load_runner(Path(__file__).with_name("run-group-sandbox.py"))
    rows = {json.loads(line)["group"]: json.loads(line) for line in args.ranking.read_text(encoding="utf-8").splitlines()}
    row = rows[args.group]
    commit = str(row["representative"])
    parent = runner.git("rev-parse", f"{commit}^", cwd=args.repo).strip()
    tests = runner.changed_tests(parent, commit)
    result = {"group": args.group, "representative": commit, "patch": str(patch_path), "test_files": tests}
    if not tests:
        result.update(status="no-test-files", returncode=125)
    else:
        temp = Path(tempfile.mkdtemp(prefix=f"model-eval-{args.group}-"))
        try:
            runner.archive(parent, temp)
            runner.copy_test_files(commit, tests, temp)
            check = subprocess.run(["git", "apply", "--check", str(patch_path)], cwd=temp, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
            if check.returncode != 0:
                result.update(status="patch-rejected", returncode=check.returncode, detail=check.stdout[-4000:])
            else:
                subprocess.run(["git", "apply", str(patch_path)], cwd=temp, check=True)
                returncode, seconds, log = runner.run_tests(temp, tests, args.timeout)
                result.update(status="model-pass" if returncode == 0 else "model-fail", returncode=returncode, seconds=round(seconds, 3))
                (temp / "model-test.log").write_text(log, encoding="utf-8")
        except Exception as exc:
            result.update(status="sandbox-error", error=repr(exc))
        finally:
            shutil.rmtree(temp, ignore_errors=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    raise SystemExit(0 if result["status"] == "model-pass" else 1)

if __name__ == "__main__":
    main()
