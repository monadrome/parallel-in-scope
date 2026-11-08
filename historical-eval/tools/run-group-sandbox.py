#!/usr/bin/env python3
"""Run historical regression tests in independent before/after sandboxes."""
from __future__ import annotations
import argparse
import json
import os
import shutil
import subprocess
import tempfile
import time
from pathlib import Path

TEST_MARKERS = ("/src/test/", "src/test/")
MODULES = ("core", "blocking-queues", "demo", "integrations")

def git(*args: str, cwd: Path | None = None) -> str:
    return subprocess.check_output(["git", *args], cwd=cwd, text=True)

def changed_tests(parent: str, commit: str) -> list[str]:
    files = git("diff", "--name-only", parent, commit, "--").splitlines()
    return [path for path in files if any(marker in path for marker in TEST_MARKERS) and "/verification/" not in f"/{path}"]

def module_for(path: str) -> str:
    for module in MODULES:
        if path.startswith(module + "/"):
            return module
    return "."

def test_name(path: str) -> str:
    return Path(path).stem

def archive(commit: str, destination: Path) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    data = subprocess.run(["git", "archive", commit], stdout=subprocess.PIPE, check=True).stdout
    subprocess.run(["tar", "-x", "-C", str(destination)], input=data, check=True)

def copy_test_files(commit: str, test_files: list[str], destination: Path) -> None:
    for path in test_files:
        target = destination / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(git("show", f"{commit}:{path}").encode())

def run_tests(root: Path, test_files: list[str], timeout: int) -> tuple[int, float, str]:
    by_module: dict[str, list[str]] = {}
    for path in test_files:
        by_module.setdefault(module_for(path), []).append(test_name(path))
    if not by_module:
        return 125, 0.0, "no test files"
    start = time.monotonic()
    output: list[str] = []
    returncode = 0
    try:
        for module, names in by_module.items():
            module_root = root if module == "." else root / module
            pom = module_root / "pom.xml"
            command = ["mvn", "-q", "-f", str(pom), f"-Dtest={','.join(sorted(set(names)))}", "test"]
            completed = subprocess.run(command, cwd=module_root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
            output.append(f"module={module} rc={completed.returncode}\n{completed.stdout[-4000:]}")
            if completed.returncode != 0:
                returncode = completed.returncode
                break
    except subprocess.TimeoutExpired as exc:
        output.append(f"timeout: {exc}")
        returncode = 124
    return returncode, time.monotonic() - start, "\n".join(output)

def run_one(repo: Path, row: dict[str, object], keep: bool, timeout: int, logs: Path) -> dict[str, object]:
    commit = str(row["representative"])
    parent = git("rev-parse", f"{commit}^", cwd=repo).strip()
    tests = changed_tests(parent, commit)
    record = {"rank": row["rank"], "group": row["group"], "representative": commit, "subject": row["subject"], "test_files": tests}
    if not tests:
        record.update(status="no-test-files", before_returncode=125, after_returncode=125)
        return record
    temp = Path(tempfile.mkdtemp(prefix=f"historical-{row['group']}-"))
    try:
        before = temp / "before"
        after = temp / "after"
        archive(parent, before)
        copy_test_files(commit, tests, before)
        archive(commit, after)
        before_rc, before_seconds, before_log = run_tests(before, tests, timeout)
        after_rc, after_seconds, after_log = run_tests(after, tests, timeout)
        logs.mkdir(parents=True, exist_ok=True)
        (logs / f"{row['rank']:03d}-{row['group']}-before.log").write_text(before_log, encoding="utf-8")
        (logs / f"{row['rank']:03d}-{row['group']}-after.log").write_text(after_log, encoding="utf-8")
        if before_rc != 0 and after_rc == 0:
            status = "verified"
        elif before_rc == 0 and after_rc == 0:
            status = "baseline-pass"
        elif after_rc != 0:
            status = "fixed-fail"
        else:
            status = "baseline-fail-fixed-unknown"
        record.update(status=status, before_returncode=before_rc, after_returncode=after_rc, before_seconds=round(before_seconds, 3), after_seconds=round(after_seconds, 3))
    except Exception as exc:  # record one group and continue with the rest
        record.update(status="sandbox-error", error=repr(exc))
    finally:
        if not keep:
            shutil.rmtree(temp, ignore_errors=True)
        else:
            record["sandbox"] = str(temp)
    return record

def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--limit", type=int)
    parser.add_argument("--group")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--keep", action="store_true")
    parser.add_argument("--logs", type=Path, default=Path("historical-eval/catalog/sandbox-logs"))
    args = parser.parse_args()
    rows = [json.loads(line) for line in args.ranking.read_text(encoding="utf-8").splitlines()]
    if args.group:
        rows = [row for row in rows if row["group"] == args.group]
    if args.limit:
        rows = rows[: args.limit]
    results = []
    for row in rows:
        print(f"running rank={row['rank']} group={row['group']}", flush=True)
        results.append(run_one(args.repo.resolve(), row, args.keep, args.timeout, args.logs))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text("\n".join(json.dumps(result, ensure_ascii=False, sort_keys=True) for result in results) + "\n", encoding="utf-8")
    counts = {}
    for result in results:
        counts[result["status"]] = counts.get(result["status"], 0) + 1
    print(json.dumps({"results": len(results), "statuses": counts, "output": str(args.output)}, sort_keys=True))

if __name__ == "__main__":
    main()
