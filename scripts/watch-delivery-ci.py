#!/usr/bin/env python3
"""Watch the CI/CD runs of one delivery to a terminal result, attributed by commit SHA.

After pushing to the development line, a session monitors the checks its own push
triggered - and only those. GitHub keys every workflow run to the push event's
head_sha, so attribution is exact: watch the runs whose head_sha equals the pushed
tip. A newer run at a later head_sha belongs to another delivery; do not watch,
retry, or fix it.

Usage:
    python3 scripts/watch-delivery-ci.py <rev> [--branch BRANCH]
        [--timeout-minutes N] [--interval-seconds N]

`<rev>` is anything `git rev-parse` resolves (a full SHA, a short SHA, or HEAD).
`--branch` only feeds the newer-delivery hint; it defaults to the development
line declared in `.agents/project-baseline.json`.

Exit codes: 0 every run succeeded; 1 at least one run failed or was cancelled;
2 usage or API error; 3 no run materialized for the SHA within the grace period.
"""

import argparse
import json
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path

GRACE_SECONDS = 120
POLL_ATTEMPTS_FOR_GRACE = 8


@dataclass
class Run:
    database_id: int
    name: str
    status: str
    conclusion: str


class GhError(Exception):
    pass


def real_gh(args):
    """Run one gh command and return parsed JSON stdout; raises GhError on failure."""
    proc = subprocess.run(
        ["gh", *args], capture_output=True, text=True, check=False
    )
    if proc.returncode != 0:
        raise GhError(proc.stderr.strip() or f"gh exited {proc.returncode}")
    try:
        return json.loads(proc.stdout)
    except json.JSONDecodeError as exc:
        raise GhError(f"gh returned non-JSON output for {' '.join(args[:2])}") from exc


def resolve_sha(rev):
    proc = subprocess.run(
        ["git", "rev-parse", rev], capture_output=True, text=True, check=False
    )
    sha = proc.stdout.strip()
    if proc.returncode != 0 or len(sha) != 40:
        raise GhError(f"cannot resolve {rev!r} to a full commit SHA")
    return sha


def resolve_repo(gh):
    return gh(["repo", "view", "--json", "nameWithOwner"])["nameWithOwner"]


def default_branch():
    baseline = Path(__file__).resolve().parents[1] / ".agents" / "project-baseline.json"
    try:
        return json.loads(baseline.read_text())["developmentLine"]
    except (OSError, json.JSONDecodeError, KeyError):
        return None


def collect_runs(payload):
    return [
        Run(
            database_id=item["id"],
            name=item["name"],
            status=item["status"],
            conclusion=item.get("conclusion") or "",
        )
        for item in payload.get("workflow_runs", [])
    ]


def fetch_runs(gh, repo, sha):
    return collect_runs(
        gh(["api", f"repos/{repo}/actions/runs?head_sha={sha}&per_page=100"])
    )


def wait_for_runs(gh, repo, sha, sleep):
    """Poll until at least one run exists for the push; GitHub needs a few seconds."""
    interval = GRACE_SECONDS // POLL_ATTEMPTS_FOR_GRACE
    for _ in range(POLL_ATTEMPTS_FOR_GRACE):
        runs = fetch_runs(gh, repo, sha)
        if runs:
            return runs
        sleep(interval)
    return []


def watch(gh, repo, sha, timeout_minutes, interval_seconds, sleep):
    deadline = time.monotonic() + timeout_minutes * 60
    runs = wait_for_runs(gh, repo, sha, sleep)
    if not runs:
        return None
    while time.monotonic() < deadline:
        if all(run.status == "completed" for run in runs):
            return runs
        sleep(interval_seconds)
        runs = fetch_runs(gh, repo, sha)
    return runs


def failed_job_names(gh, repo, run_id):
    payload = gh(["api", f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100"])
    return [
        job["name"]
        for job in payload.get("jobs", [])
        if job.get("conclusion") not in (None, "success", "skipped", "neutral")
    ]


def find_newer_delivery(branch_payload, our_sha):
    """Branch runs arrive newest first; a newest run that is not ours means the branch moved."""
    for item in branch_payload.get("workflow_runs", []):
        head_sha = item.get("head_sha")
        return head_sha if head_sha and head_sha != our_sha else None
    return None


def decide(runs):
    """(exit code, per-run summary lines) for a fully completed run set."""
    failed = [run for run in runs if run.conclusion != "success"]
    lines = [f"{run.name}: {run.conclusion}" for run in runs]
    return (1 if failed else 0), lines


def main(argv=None, gh=real_gh, sleep=time.sleep, out=print):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("rev", help="pushed commit (full/short SHA, or HEAD)")
    parser.add_argument("--branch", default=None, help="branch for the newer-delivery hint")
    parser.add_argument("--timeout-minutes", type=int, default=90)
    parser.add_argument("--interval-seconds", type=int, default=30)
    args = parser.parse_args(argv)

    try:
        sha = resolve_sha(args.rev)
        repo = resolve_repo(gh)
    except GhError as exc:
        out(f"error: {exc}")
        return 2

    try:
        runs = watch(gh, repo, sha, args.timeout_minutes, args.interval_seconds, sleep)
    except GhError as exc:
        out(f"error: {exc}")
        return 2
    if runs is None:
        out(f"error: no workflow run materialized for {sha} within {GRACE_SECONDS}s")
        return 3

    incomplete = [run for run in runs if run.status != "completed"]
    if incomplete:
        names = ", ".join(run.name for run in incomplete)
        out(f"error: timed out waiting for: {names}")
        return 2

    code, lines = decide(runs)
    for line in lines:
        out(line)
    for run in runs:
        if run.conclusion != "success":
            try:
                for name in failed_job_names(gh, repo, run.database_id):
                    out(f"  failed job [{run.name}]: {name}")
            except GhError:
                out(f"  failed job [{run.name}]: <job list unavailable>")

    branch = args.branch or default_branch()
    if branch:
        try:
            newer = find_newer_delivery(
                gh(["api", f"repos/{repo}/actions/runs?branch={branch}&per_page=5"]),
                sha,
            )
        except GhError:
            newer = None
        if newer:
            out(
                f"note: branch {branch} has moved to {newer[:7]}; "
                "runs at that tip belong to that delivery - do not retry or fix them"
            )
    out("delivery checks green" if code == 0 else "delivery checks FAILED")
    return code


if __name__ == "__main__":
    sys.exit(main())
