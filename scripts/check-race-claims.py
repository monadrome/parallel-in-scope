#!/usr/bin/env python3
"""Verify registered race/ordering claims by reverse mutation, tests, or code proof.

The registry lives in `verification/race-claims/claims.json`. Each claim is one
of:

- `mutation`: apply one or more exact source replacements in a throwaway git
  worktree, run the cited tests, and expect a failure. A failure proves that the
  cited tests detect the reversed ordering; the raw Maven log is retained for
  audit.
- `existing-test`: run an existing deterministic interleaving test in a throwaway
  worktree at HEAD and record it as the proof. No source mutation.
- `code-proof`: a code-level interleaving or JMM argument is the proof; no test
  is run. Use this only when the argument is concrete and reviewable.
- `needs-jcstress`: no dynamic JMM harness exists. This is a statement about
  tooling, not a claim that the code reasoning is wrong.

The main worktree is never modified. Results are written incrementally to
`target/race-claims/results.json`; failure logs are written to
`target/race-claims/<claim-id>.log`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CLAIMS = ROOT / "verification" / "race-claims" / "claims.json"
RESULTS_DIR = ROOT / "target" / "race-claims"
DEFAULT_TIMEOUT = 300
MAX_LOG_BYTES = 200_000

# Results that mean the harness itself failed, as opposed to a recorded test
# gap. `UNPROVEN` and `NEEDS_JCSTRESS` describe missing evidence and are
# reported without failing; these four mean the run did not measure anything.
BROKEN_RESULTS = frozenset({"INVALID_MUTATION", "ERROR", "BROKEN", "TIMEOUT"})


def run(command: list[str], cwd: Path, timeout: int = DEFAULT_TIMEOUT):
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, timeout=timeout)


def run_tests(cwd: Path, tests: str, timeout: int):
    command = ["mvn", "-B", "-ntp", f"-Dtest={tests}", "test"]
    try:
        return command, run(command, cwd, timeout=timeout)
    except subprocess.TimeoutExpired as exc:
        return command, exc


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
        return "PROVEN_TIMEOUT", "tests exceeded the timeout after the mutation; inspect the log"
    output = (result.stdout or "") + (result.stderr or "")
    if (
        "COMPILATION ERROR" in output
        or "Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin" in output
    ):
        return "INVALID_MUTATION", "the mutation did not compile"
    if result.returncode != 0:
        return "PROVEN", "the mutation caused a cited test to fail; inspect the log"
    return "UNPROVEN", "tests passed with the mutation; no cited test detected the reordering"


def save_log(claim_id: str, result) -> str:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    path = RESULTS_DIR / f"{claim_id}.log"
    if isinstance(result, subprocess.TimeoutExpired):
        text = (result.stdout or "") + (result.stderr or "") + "\n[timeout]"
    else:
        text = (result.stdout or "") + (result.stderr or "")
    path.write_text(text[-MAX_LOG_BYTES:], encoding="utf-8")
    return str(path)


def git_head() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True, capture_output=True, text=True
        ).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return ""


def registry_sha() -> str:
    return hashlib.sha256(CLAIMS.read_bytes()).hexdigest()[:16]


def utc_now() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def with_worktree(callback):
    worktree = Path(tempfile.mkdtemp(prefix="race-claim-"))
    try:
        added = run(["git", "worktree", "add", "--detach", str(worktree), "HEAD"], ROOT, timeout=60)
        if added.returncode != 0:
            raise RuntimeError(added.stderr.strip()[-400:])
        return callback(worktree)
    finally:
        run(["git", "worktree", "remove", "--force", str(worktree)], ROOT, timeout=60)
        shutil.rmtree(worktree, ignore_errors=True)


def run_claim(claim: dict, timeout: int) -> dict:
    method = claim["method"]
    if method == "code-proof":
        return {
            "id": claim["id"],
            "result": "PROVEN_BY_REASONING",
            "note": claim.get("proof") or claim.get("note", ""),
        }
    if method == "needs-jcstress":
        return {"id": claim["id"], "result": "NEEDS_JCSTRESS", "note": claim.get("note", "")}

    if method == "existing-test":
        try:
            def exercise(worktree: Path) -> dict:
                command, result = run_tests(worktree, claim["tests"], timeout)
                log = save_log(claim["id"], result)
                if isinstance(result, subprocess.TimeoutExpired):
                    return {
                        "id": claim["id"],
                        "result": "TIMEOUT",
                        "note": "existing test timed out",
                        "test_command": " ".join(command),
                        "log": log,
                    }
                if result.returncode == 0:
                    return {
                        "id": claim["id"],
                        "result": "PROVEN_BY_TEST",
                        "note": claim.get("note", ""),
                        "test_command": " ".join(command),
                        "log": log,
                    }
                return {
                    "id": claim["id"],
                    "result": "BROKEN",
                    "note": "existing test failed without a mutation",
                    "test_command": " ".join(command),
                    "log": log,
                }

            return with_worktree(exercise)
        except Exception as exc:  # noqa: BLE001 - reported as an error
            return {"id": claim["id"], "result": "ERROR", "note": str(exc)[-400:]}

    if method != "mutation":
        return {"id": claim["id"], "result": "ERROR", "note": f"unknown method {method}"}

    try:
        def exercise(worktree: Path) -> dict:
            apply_mutations(worktree, claim)
            command, result = run_tests(worktree, claim["tests"], timeout)
            log = save_log(claim["id"], result)
            status, note = classify_mutation(result)
            return {
                "id": claim["id"],
                "result": status,
                "note": note,
                "test_command": " ".join(command),
                "log": log,
            }

        return with_worktree(exercise)
    except ValueError as exc:
        return {"id": claim["id"], "result": "INVALID_MUTATION", "note": str(exc)}
    except Exception as exc:  # noqa: BLE001 - reported as an error
        return {"id": claim["id"], "result": "ERROR", "note": str(exc)[-400:]}


def record(results: list[dict], entry: dict) -> None:
    results[:] = [existing for existing in results if existing["id"] != entry["id"]]
    results.append(entry)
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    (RESULTS_DIR / "results.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"{entry['result']:20s} {entry['id']:45s} {entry['note']}", flush=True)


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

    provenance = {
        "source_head": git_head(),
        "registry_sha": registry_sha(),
        "timestamp": utc_now(),
    }
    results_path = RESULTS_DIR / "results.json"
    results: list[dict] = []
    if not args.fresh and results_path.exists():
        try:
            results = json.loads(results_path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            results = []
    ran: list[dict] = []
    for claim in claims:
        entry = run_claim(claim, args.timeout)
        entry.update(provenance)
        record(results, entry)
        ran.append(entry)

    print("\nSummary")
    for entry in results:
        print(f"  {entry['result']:20s} {entry['id']}")
    return exit_code(ran)


def exit_code(entries: list[dict]) -> int:
    broken = [entry["id"] for entry in entries if entry["result"] in BROKEN_RESULTS]
    if broken:
        print(f"harness broken for: {', '.join(broken)}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
