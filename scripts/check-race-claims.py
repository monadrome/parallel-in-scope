#!/usr/bin/env python3
"""Verify registered race/ordering claims by reverse mutation or interleaving tests.

The registry lives in `verification/race-claims/claims.json`. Each claim is one
of:

- `mutation`: apply one or more exact source replacements in a throwaway git
  worktree, run the cited tests, and expect a failure. A failure proves that the
  cited tests detect the reversed ordering.
- `existing-test`: run an existing deterministic interleaving test and record it
  as the proof. No source change is made.
- `needs-jcstress`: the claim is a JMM/happens-before claim that needs a
  jcstress-style harness; no test is run.

The main worktree is never modified. Results are written incrementally to
`target/race-claims/results.json`.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLAIMS = ROOT / "verification" / "race-claims" / "claims.json"
RESULTS_DIR = ROOT / "target" / "race-claims"
DEFAULT_TIMEOUT = 300


def run(command: list[str], cwd: Path, timeout: int = DEFAULT_TIMEOUT):
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=timeout)


def run_tests(cwd: Path, tests: str, timeout: int):
    command = ["mvn", "-B", "-ntp", f"-Dtest={tests}", "test"]
    try:
        return run(command, cwd, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        return exc


def apply_mutations(worktree: Path, claim: dict) -> None:
    for mutation in claim.get("mutations", []):
        path = worktree / mutation.get("file", claim["file"])
        text = path.read_text(encoding="utf-8")
        find = mutation["find"]
        replace = mutation["replace"]
        count = text.count(find)
        if count != 1:
            raise ValueError(f"{claim['id']}: find occurs {count} times, expected exactly 1")
        path.write_text(text.replace(find, replace, 1), encoding="utf-8")


def classify_mutation(result) -> tuple[str, str]:
    if isinstance(result, subprocess.TimeoutExpired):
        return "PROVEN_TIMEOUT", "tests exceeded the timeout after the mutation"
    output = (result.stdout or "") + (result.stderr or "")
    if (
        "COMPILATION ERROR" in output
        or "Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin" in output
    ):
        return "INVALID_MUTATION", "the mutation did not compile"
    if result.returncode != 0:
        return "PROVEN", "the mutation caused a cited test to fail"
    return "UNPROVEN", "tests passed with the mutation; no cited test detected the reordering"


def record(results: list[dict], entry: dict) -> None:
    results[:] = [existing for existing in results if existing["id"] != entry["id"]]
    results.append(entry)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"{entry['result']:18s} {entry['id']:45s} {entry['note']}", flush=True)


def run_claim(claim: dict, timeout: int) -> dict:
    method = claim["method"]
    if method == "needs-jcstress":
        return {"id": claim["id"], "result": "NEEDS_JCSTRESS", "note": claim.get("note", "")}
    if method == "existing-test":
        result = run_tests(ROOT, claim["tests"], timeout)
        if isinstance(result, subprocess.TimeoutExpired):
            return {"id": claim["id"], "result": "TIMEOUT", "note": "existing test timed out"}
        if result.returncode == 0:
            return {"id": claim["id"], "result": "PROVEN_BY_TEST", "note": claim.get("note", "")}
        return {"id": claim["id"], "result": "BROKEN", "note": "existing test failed without a mutation"}
    if method != "mutation":
        return {"id": claim["id"], "result": "ERROR", "note": f"unknown method {method}"}

    worktree = Path(tempfile.mkdtemp(prefix=f"race-{claim['id']}-"))
    try:
        added = run(["git", "worktree", "add", "--detach", str(worktree), "HEAD"], ROOT, timeout=60)
        if added.returncode != 0:
            return {"id": claim["id"], "result": "ERROR", "note": added.stderr.strip()[-400:]}
        try:
            apply_mutations(worktree, claim)
        except Exception as exc:  # noqa: BLE001 - reported as an invalid mutation
            return {"id": claim["id"], "result": "INVALID_MUTATION", "note": str(exc)}
        result = run_tests(worktree, claim["tests"], timeout)
        status, note = classify_mutation(result)
        return {"id": claim["id"], "result": status, "note": note}
    finally:
        run(["git", "worktree", "remove", "--force", str(worktree)], ROOT, timeout=60)
        shutil.rmtree(worktree, ignore_errors=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--id", action="append", help="run only the named claim; repeatable")
    parser.add_argument("--timeout", type=int, default=DEFAULT_TIMEOUT, help="per-claim Maven timeout")
    parser.add_argument("--fresh", action="store_true", help="discard previous results before running")
    args = parser.parse_args(argv)

    data = json.loads(CLAIMS.read_text(encoding="utf-8"))
    claims = [claim for claim in data["claims"] if not args.id or claim["id"] in args.id]
    if not claims:
        print("no matching claims", file=sys.stderr)
        return 1

    results_path = RESULTS_DIR / "results.json"
    results: list[dict] = []
    if not args.fresh and results_path.exists():
        try:
            results = json.loads(results_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            results = []
    for claim in claims:
        record(results, run_claim(claim, args.timeout))

    print("\nSummary")
    for entry in results:
        print(f"  {entry['result']:18s} {entry['id']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
