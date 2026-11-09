#!/usr/bin/env python3
"""Run a resumable, coarse-feedback repair loop for one historical group.

The existing run-codex-group.py remains the frozen one-shot baseline.  This
runner is an opt-in execution path: every attempt starts from the historical
parent snapshot, model output is captured privately, and the model only sees
coarse verifier feedback on later attempts.
"""
from __future__ import annotations

import argparse
import difflib
import hashlib
import json
import os
import signal
import shlex
import shutil
import subprocess
import tempfile
import time
from pathlib import Path
from typing import Any


SOURCE_SUFFIXES = {".java", ".kt", ".py", ".ts", ".js", ".go", ".rs"}
CONTROL_FILES = {"task.md", "feedback.json", "candidate.patch"}


def git(repo: Path, *args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=repo, text=True)


def sha256_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def load_group(ranking_path: Path, groups_path: Path, rank: int) -> tuple[dict[str, Any], dict[str, Any]]:
    ranking = next(row for row in read_jsonl(ranking_path) if int(row["rank"]) == rank)
    groups = {row["group"]: row for row in read_jsonl(groups_path)}
    return ranking, groups[ranking["group"]]


def source_paths(group: dict[str, Any]) -> list[str]:
    return sorted(
        path
        for path in group.get("production_files", [])
        if Path(path).suffix in SOURCE_SUFFIXES and not path.endswith("package-info.java")
    )


def build_workspace(repo: Path, parent: str, paths: list[str], prefix: str) -> tuple[Path, dict[str, str]]:
    workspace = Path(tempfile.mkdtemp(prefix=prefix))
    originals: dict[str, str] = {}
    for path in paths:
        try:
            content = git(repo, "show", f"{parent}:{path}")
        except subprocess.CalledProcessError:
            continue
        originals[path] = content
        target = workspace / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
    return workspace, originals


def terminate_process_group(process: subprocess.Popen[str]) -> None:
    """Terminate the model and all descendants created in its process group."""
    if process.poll() is not None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except (ProcessLookupError, subprocess.TimeoutExpired):
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass


def run_model(
    provider: str,
    model: str,
    effort: str,
    workspace: Path,
    prompt: str,
    timeout: int,
    log_path: Path,
) -> tuple[int, str, bool, list[str]]:
    if provider == "codex":
        command = [
            "codex",
            "exec",
            "--ephemeral",
            "--skip-git-repo-check",
            "-m",
            model,
            "-c",
            f"model_reasoning_effort={effort}",
            "-s",
            "workspace-write",
            "-C",
            str(workspace),
            prompt,
        ]
    elif provider == "zcode":
        command = [
            "zcode",
            "--prompt",
            prompt,
            "--model",
            model,
            "--effort",
            effort,
            "--cwd",
            str(workspace),
            "--mode",
            "yolo",
            "--no-browser",
            "--no-color",
        ]
    else:
        raise ValueError(f"unsupported provider: {provider}")

    log_path.parent.mkdir(parents=True, exist_ok=True)
    # Redirect directly to a file instead of PIPE. Codex/ZCode may spawn
    # descendants that inherit stdout; waiting on a pipe can then outlive the
    # requested timeout even after the direct child has stopped producing data.
    with log_path.open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command,
            cwd=workspace,
            stdout=log,
            stderr=subprocess.STDOUT,
            text=True,
            start_new_session=True,
        )
        try:
            process.wait(timeout=timeout)
            returncode = process.returncode
            timed_out = False
        except subprocess.TimeoutExpired:
            terminate_process_group(process)
            returncode = 124
            timed_out = True
    output = log_path.read_text(encoding="utf-8", errors="replace")
    return returncode, output, timed_out, command


def patch_for_workspace(workspace: Path, originals: dict[str, str]) -> tuple[str, list[str]]:
    patch_parts: list[str] = []
    for path, original in originals.items():
        modified_path = workspace / path
        modified = modified_path.read_text(encoding="utf-8") if modified_path.exists() else ""
        patch_parts.extend(
            difflib.unified_diff(
                original.splitlines(True),
                modified.splitlines(True),
                fromfile=f"a/{path}",
                tofile=f"b/{path}",
            )
        )

    out_of_scope: list[str] = []
    allowed = set(originals) | CONTROL_FILES
    for path in workspace.rglob("*"):
        if not path.is_file():
            continue
        relative = path.relative_to(workspace).as_posix()
        if relative not in allowed:
            out_of_scope.append(relative)
    return "".join(patch_parts), sorted(out_of_scope)


def apply_patch(workspace: Path, patch_path: Path) -> tuple[bool, str]:
    if not patch_path.exists() or not patch_path.read_text(encoding="utf-8"):
        return True, ""
    check = subprocess.run(
        ["git", "apply", "--check", str(patch_path)],
        cwd=workspace,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    if check.returncode != 0:
        return False, check.stdout[-4000:]
    applied = subprocess.run(
        ["git", "apply", str(patch_path)],
        cwd=workspace,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    return applied.returncode == 0, applied.stdout[-4000:]


def coarse_from_evaluation(status: str) -> str:
    return {
        "model-pass": "PASS",
        "model-fail": "PUBLIC_TEST_FAIL",
        "patch-rejected": "PATCH_REJECTED",
        "sandbox-error": "VERIFIER_ERROR",
        "no-test-files": "NO_TEST_FILES",
    }.get(status, status.upper())


def coarse_from_result(result: dict[str, Any]) -> str:
    """Map evaluator output to feedback safe to return to a repair attempt."""
    status = str(result.get("status", "unknown"))
    returncode = result.get("returncode")
    detail = str(result.get("detail", "")).lower()
    phase = str(result.get("phase", "")).lower()
    if status in {"compile-timeout", "compile_timeout"} or phase == "compile" and returncode == 124:
        return "COMPILE_TIMEOUT"
    if returncode == 124 or "timeout" in detail:
        return "TEST_TIMEOUT"
    if status in {"compile-fail", "compile_failure", "compile-error"} or "compilation failure" in detail or "compilation error" in detail:
        return "COMPILE_FAIL"
    return coarse_from_evaluation(status)


def evaluate_public(repo: Path, ranking: Path, group: str, patch: Path, output: Path, timeout: int) -> dict[str, Any]:
    evaluator = Path(__file__).with_name("evaluate-group-patch.py")
    command = [
        "python3",
        str(evaluator),
        str(ranking),
        group,
        str(patch),
        "-o",
        str(output),
        "--repo",
        str(repo),
        "--timeout",
        str(timeout),
    ]
    try:
        completed = subprocess.run(
            command,
            cwd=repo,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            timeout=timeout + 30,
            check=False,
        )
    except subprocess.TimeoutExpired:
        return {"status": "evaluator-timeout", "coarse_status": "TEST_TIMEOUT", "returncode": 124}
    if output.exists():
        result = json.loads(output.read_text(encoding="utf-8"))
        result["coarse_status"] = coarse_from_result(result)
        return result
    return {"status": "evaluator-missing", "coarse_status": "VERIFIER_ERROR", "returncode": completed.returncode}


def task_text() -> str:
    return (
        "请检查提供的生产源码，找出潜在的行为缺陷并修复。保持现有 API、Java 8 兼容性和线程安全语义；"
        "只修改允许的生产源码，不查看 Git 历史、测试、文档或其他答案文件。完成后直接修改工作区。\n"
    )


def prompt_for_attempt(attempt: int, feedback: str | None) -> str:
    if attempt == 1:
        return (
            "Inspect task.md and the provided production source files only. Repair the latent behavioral defect directly in the workspace. "
            "Do not inspect git history, tests, oracle files, documentation, or commit metadata. Use only safe local compilation if useful."
        )
    return (
        "Re-audit the current candidate repair against task.md and the provided production source files. "
        "The previous external verifier returned only this coarse status: "
        f"{feedback or 'UNKNOWN'}. Do not inspect tests, oracle files, documentation, Git history, or verifier logs. "
        "Correct the candidate directly in the workspace and preserve the API, Java 8 compatibility, and thread-safety semantics."
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("groups", type=Path)
    parser.add_argument("rank", type=int)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--provider", choices=("codex", "zcode"), default="codex")
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--reasoning-effort", "--effort", dest="effort", default="medium")
    parser.add_argument("--strategy", choices=("oneshot", "repair-loop"), default="oneshot")
    parser.add_argument("--max-attempts", type=int, default=2)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--eval-timeout", type=int, default=180)
    parser.add_argument("--keep-workspaces", action="store_true")
    args = parser.parse_args()

    if args.strategy == "oneshot":
        max_attempts = 1
    else:
        max_attempts = max(1, args.max_attempts)

    repo = args.repo.resolve()
    ranking_path = args.ranking.resolve()
    groups_path = args.groups.resolve()
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    row, group = load_group(ranking_path, groups_path, args.rank)
    commit = str(row["representative"])
    parent = git(repo, "rev-parse", f"{commit}^").strip()
    paths = source_paths(group)

    config = {
        "rank": args.rank,
        "group": row["group"],
        "representative": commit,
        "parent": parent,
        "provider": args.provider,
        "model": args.model,
        "effort": args.effort,
        "strategy": args.strategy,
        "max_attempts": max_attempts,
        "timeout": args.timeout,
        "eval_timeout": args.eval_timeout,
        "source_paths": paths,
    }
    config_hash = sha256_text(json.dumps(config, ensure_ascii=False, sort_keys=True))
    run_id = f"rank-{args.rank:03d}-{config_hash[:12]}"
    attempts: list[dict[str, Any]] = []
    previous_patch: Path | None = None
    feedback: str | None = None
    final_status = "no-change"

    def result_payload() -> dict[str, Any]:
        last = attempts[-1] if attempts else {}
        return {
            "schema_version": 1,
            "run_id": run_id,
            "config_hash": config_hash,
            "config": config,
            "rank": args.rank,
            "group": row["group"],
            "representative": commit,
            "parent": parent,
            "provider": args.provider,
            "model": args.model,
            "effort": args.effort,
            "strategy": args.strategy,
            "max_attempts": max_attempts,
            "public_status": last.get("public_status", "not-run"),
            "public_coarse_status": last.get("public_coarse_status", "NOT_RUN"),
            "final_status": final_status,
            "attempts": attempts,
        }

    # Resume only an identical run.  A different configuration must use a
    # different output path so old results can never be silently reused.
    if output.exists():
        existing = json.loads(output.read_text(encoding="utf-8"))
        if existing.get("config_hash") != config_hash or existing.get("run_id") != run_id:
            raise SystemExit(
                f"existing output has a different run configuration: {output}; choose another output path"
            )
        attempts = list(existing.get("attempts", []))
        final_status = str(existing.get("final_status", "no-change"))
        if attempts:
            last = attempts[-1]
            public_coarse = str(last.get("public_coarse_status", last.get("evaluation", {}).get("coarse_status", "UNKNOWN")))
            if public_coarse in {"NOT_RUN", "UNKNOWN"}:
                public_coarse = {
                    "model-timeout": "TIMEOUT",
                    "test-timeout": "TEST_TIMEOUT",
                    "out-of-scope": "OUT_OF_SCOPE",
                    "no-change": "NO_CHANGE",
                }.get(str(last.get("status")), public_coarse)
            feedback = public_coarse
            patch_value = last.get("patch")
            if patch_value and Path(patch_value).exists():
                previous_patch = Path(patch_value)
        if final_status == "model-pass" or len(attempts) >= max_attempts:
            print(json.dumps(existing, ensure_ascii=False, sort_keys=True))
            return 0 if final_status == "model-pass" else 1

    # Recover attempt records if the process died after writing an attempt but
    # before updating the top-level result file.
    known_attempts = {str(item.get("attempt_id")) for item in attempts}
    for attempt_file in sorted(output.parent.glob(f"{run_id}-attempt-*/attempt.json")):
        try:
            record = json.loads(attempt_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if str(record.get("attempt_id")) not in known_attempts:
            attempts.append(record)
            known_attempts.add(str(record.get("attempt_id")))
    attempts.sort(key=lambda item: int(item.get("attempt", 0)))
    if attempts:
        last = attempts[-1]
        feedback = str(last.get("public_coarse_status", last.get("evaluation", {}).get("coarse_status", "UNKNOWN")))
        if feedback in {"NOT_RUN", "UNKNOWN"}:
            feedback = {
                "model-timeout": "TIMEOUT",
                "test-timeout": "TEST_TIMEOUT",
                "out-of-scope": "OUT_OF_SCOPE",
                "no-change": "NO_CHANGE",
            }.get(str(last.get("status")), feedback)
        patch_value = last.get("patch")
        if patch_value and Path(patch_value).exists():
            previous_patch = Path(patch_value)
        final_status = str(last.get("status", final_status))
    start_attempt = len(attempts) + 1

    for number in range(start_attempt, max_attempts + 1):
        attempt_id = f"{run_id}-attempt-{number:03d}"
        attempt_dir = output.parent / attempt_id
        attempt_dir.mkdir(parents=True, exist_ok=True)
        workspace, originals = build_workspace(repo, parent, paths, f"repair-{args.rank:03d}-{number:03d}-")
        apply_status = "not-needed"
        apply_detail = ""
        if previous_patch is not None:
            applied, apply_detail = apply_patch(workspace, previous_patch)
            apply_status = "applied" if applied else "apply-failed"
            if not applied:
                feedback = "PATCH_REJECTED"

        (workspace / "task.md").write_text(task_text(), encoding="utf-8")
        if feedback:
            (workspace / "feedback.json").write_text(json.dumps({"status": feedback}, indent=2) + "\n", encoding="utf-8")
        prompt = prompt_for_attempt(number, feedback)
        prompt_hash = sha256_text(prompt)
        start = time.monotonic()
        session_path = attempt_dir / "session.log"
        returncode, session_output, timed_out, command = run_model(
            args.provider, args.model, args.effort, workspace, prompt, args.timeout, session_path
        )
        elapsed = time.monotonic() - start
        patch_text, out_of_scope = patch_for_workspace(workspace, originals)
        patch_path = attempt_dir / "candidate.patch"
        patch_path.write_text(patch_text, encoding="utf-8")
        patch_hash = sha256_file(patch_path)
        evaluation: dict[str, Any] = {"status": "not-run", "coarse_status": "NOT_RUN"}
        if out_of_scope:
            attempt_status = "out-of-scope"
            feedback = "OUT_OF_SCOPE"
        elif timed_out:
            attempt_status = "model-timeout"
            feedback = "TIMEOUT"
        elif not patch_text:
            attempt_status = "no-change"
            feedback = "NO_CHANGE"
        else:
            eval_path = attempt_dir / "public-evaluation.json"
            evaluation = evaluate_public(repo, ranking_path, str(row["group"]), patch_path, eval_path, args.eval_timeout)
            feedback = str(evaluation.get("coarse_status", "VERIFIER_ERROR"))
            if feedback == "PASS":
                attempt_status = "model-pass"
            elif feedback in {"COMPILE_TIMEOUT", "TEST_TIMEOUT"}:
                attempt_status = "test-timeout"
            else:
                attempt_status = "model-fail"

        public_status = str(evaluation.get("status", "not-run"))
        public_coarse_status = str(evaluation.get("coarse_status", "NOT_RUN"))

        attempt_record = {
            "attempt_id": attempt_id,
            "attempt": number,
            "status": attempt_status,
            "public_status": public_status,
            "public_coarse_status": public_coarse_status,
            "model_returncode": returncode,
            "timed_out": timed_out,
            "seconds": round(elapsed, 3),
            "provider": args.provider,
            "model": args.model,
            "effort": args.effort,
            "prompt_hash": prompt_hash,
            "patch_hash": patch_hash,
            "patch": str(patch_path),
            "session_log": str(session_path),
            "workspace": str(workspace),
            "apply_status": apply_status,
            "apply_detail": apply_detail,
            "out_of_scope_files": out_of_scope,
            "evaluation": evaluation,
            "command": command,
        }
        (attempt_dir / "attempt.json").write_text(json.dumps(attempt_record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        attempts.append(attempt_record)
        final_status = "model-pass" if feedback == "PASS" else attempt_status
        previous_patch = patch_path
        output.write_text(json.dumps(result_payload(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        if feedback == "PASS":
            break
        if not args.keep_workspaces:
            shutil.rmtree(workspace, ignore_errors=True)

    result = result_payload()
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, sort_keys=True))
    return 0 if final_status == "model-pass" else 1


if __name__ == "__main__":
    raise SystemExit(main())
