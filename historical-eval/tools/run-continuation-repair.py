#!/usr/bin/env python3
"""Run a two-turn Codex repair using one persisted session.

This is a separate research track from run-repair-loop.py's fresh-session
repair loop. The evaluator still returns only coarse feedback; the second turn
resumes the same Codex thread in the same model workspace.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import signal
import subprocess
import time
from pathlib import Path
from typing import Any


def load_runner(path: Path):
    spec = importlib.util.spec_from_file_location("repair_runner", path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def run_persisted(command: list[str], cwd: Path, log_path: Path, timeout: int) -> tuple[int, bool]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        try:
            process.wait(timeout=timeout)
            return int(process.returncode), False
        except subprocess.TimeoutExpired:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except (ProcessLookupError, subprocess.TimeoutExpired):
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            return 124, True


def thread_id_from_jsonl(path: Path) -> str | None:
    pattern = re.compile(r'"thread_id"\s*:\s*"([^"]+)"')
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if '"type":"thread.started"' in line or '"type": "thread.started"' in line:
            match = pattern.search(line)
            if match:
                return match.group(1)
    return None


def attempt_record(
    runner: Any,
    repo: Path,
    ranking: Path,
    group: str,
    workspace: Path,
    originals: dict[str, str],
    attempt_dir: Path,
    command: list[str],
    returncode: int,
    timed_out: bool,
    started: float,
    eval_timeout: int,
) -> dict[str, Any]:
    patch_text, out_of_scope = runner.patch_for_workspace(workspace, originals)
    patch_path = attempt_dir / "candidate.patch"
    patch_path.write_text(patch_text, encoding="utf-8")
    evaluation: dict[str, Any] = {"status": "not-run", "coarse_status": "NOT_RUN"}
    if timed_out:
        status = "model-timeout"
        coarse = "TIMEOUT"
    elif out_of_scope:
        status = "out-of-scope"
        coarse = "OUT_OF_SCOPE"
    elif not patch_text:
        status = "no-change"
        coarse = "NO_CHANGE"
    else:
        evaluation = runner.evaluate_public(
            repo,
            ranking,
            group,
            patch_path,
            attempt_dir / "public-evaluation.json",
            eval_timeout,
        )
        coarse = str(evaluation.get("coarse_status", "VERIFIER_ERROR"))
        status = "model-pass" if coarse == "PASS" else "model-fail"
    return {
        "attempt": int(attempt_dir.name.split("-")[-1]),
        "status": status,
        "public_status": evaluation.get("status", "not-run"),
        "public_coarse_status": coarse,
        "model_returncode": returncode,
        "timed_out": timed_out,
        "seconds": round(time.monotonic() - started, 3),
        "patch": str(patch_path),
        "out_of_scope_files": out_of_scope,
        "evaluation": evaluation,
        "command": command,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("groups", type=Path)
    parser.add_argument("rank", type=int)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--effort", default="medium")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--eval-timeout", type=int, default=180)
    args = parser.parse_args()

    repo = args.repo.resolve()
    ranking = args.ranking.resolve()
    groups = args.groups.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    runner = load_runner(Path(__file__).with_name("run-repair-loop.py"))
    row, group = runner.load_group(ranking, groups, args.rank)
    commit = str(row["representative"])
    parent = runner.git(repo, "rev-parse", f"{commit}^").strip()
    paths = runner.source_paths(group)
    workspace, originals = runner.build_workspace(repo, parent, paths, f"continuation-{args.rank:03d}-")
    (workspace / "task.md").write_text(runner.task_text(), encoding="utf-8")
    attempts: list[dict[str, Any]] = []
    session_id: str | None = None

    first_prompt = runner.prompt_for_attempt(1, None)
    first_dir = output.parent / "attempt-001"
    first_log = first_dir / "session.jsonl"
    first_command = [
        "codex", "exec", "--json", "--skip-git-repo-check", "-m", args.model,
        "-c", f"model_reasoning_effort={args.effort}", "-s", "workspace-write",
        "-C", str(workspace), first_prompt,
    ]
    started = time.monotonic()
    first_returncode, first_timeout = run_persisted(first_command, workspace, first_log, args.timeout)
    session_id = thread_id_from_jsonl(first_log)
    first = attempt_record(
        runner, repo, ranking, str(row["group"]), workspace, originals, first_dir,
        first_command, first_returncode, first_timeout, started, args.eval_timeout,
    )
    first["prompt"] = first_prompt
    first["session_id"] = session_id
    attempts.append(first)

    if first["status"] == "model-pass":
        final_status = "model-pass"
    elif first["timed_out"]:
        final_status = "model-timeout"
    elif not session_id:
        final_status = "continuation-unavailable"
    else:
        coarse = str(first["public_coarse_status"])
        feedback = {"status": coarse}
        (workspace / "feedback.json").write_text(json.dumps(feedback, indent=2) + "\n", encoding="utf-8")
        second_prompt = runner.prompt_for_attempt(2, coarse)
        second_dir = output.parent / "attempt-002"
        second_log = second_dir / "session.jsonl"
        second_command = [
            "codex", "exec", "resume", session_id, "--json", "--skip-git-repo-check",
            "-m", args.model, second_prompt,
        ]
        started = time.monotonic()
        second_returncode, second_timeout = run_persisted(second_command, workspace, second_log, args.timeout)
        second = attempt_record(
            runner, repo, ranking, str(row["group"]), workspace, originals, second_dir,
            second_command, second_returncode, second_timeout, started, args.eval_timeout,
        )
        second["prompt"] = second_prompt
        second["session_id"] = session_id
        second["feedback_file"] = str(workspace / "feedback.json")
        attempts.append(second)
        final_status = second["status"]

    result = {
        "schema_version": 1,
        "mode": "continuation-repair",
        "rank": args.rank,
        "group": row["group"],
        "representative": commit,
        "parent": parent,
        "model": args.model,
        "effort": args.effort,
        "workspace": str(workspace),
        "session_id": session_id,
        "final_status": final_status,
        "attempts": attempts,
    }
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if final_status == "model-pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
