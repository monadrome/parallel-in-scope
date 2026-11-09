#!/usr/bin/env python3
"""Run a reproducible model matrix over the eligible historical groups.

This scheduler keeps one-shot as the comparable baseline. It writes raw model
artifacts to a private directory and only emits a compact JSONL index. Rescue
hidden tests are evaluated out of band for ranks explicitly listed by the
caller; their source never enters a model workspace.
"""
from __future__ import annotations

import argparse
import concurrent.futures
import hashlib
import json
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256_text(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def run_one(
    repo: Path,
    ranking: Path,
    groups: Path,
    sandbox: dict[str, dict[str, Any]],
    row: dict[str, Any],
    provider: str,
    model: str,
    effort: str,
    strategy: str,
    max_attempts: int,
    timeout: int,
    eval_timeout: int,
    output_root: Path,
    rescue_ranks: set[int],
    rescue_evaluator: Path | None,
    rescue_private_root: Path | None,
) -> dict[str, Any]:
    rank = int(row["rank"])
    group = str(row["group"])
    target = output_root / f"{rank:03d}-{group}"
    target.mkdir(parents=True, exist_ok=True)
    result_path = target / "result.json"
    config = {
        "provider": provider,
        "model": model,
        "effort": effort,
        "strategy": strategy,
        "max_attempts": max_attempts,
        "timeout": timeout,
        "eval_timeout": eval_timeout,
        "rank": rank,
        "group": group,
    }
    config_hash = sha256_text(json.dumps(config, sort_keys=True))
    if result_path.exists():
        try:
            existing = json.loads(result_path.read_text(encoding="utf-8"))
            if existing.get("config_hash") == config_hash:
                return existing
        except (OSError, json.JSONDecodeError):
            pass

    historical_status = sandbox.get(group, {}).get("status", "not-run")
    eligible = historical_status == "verified" or rank in rescue_ranks
    if not eligible:
        result = {**config, "config_hash": config_hash, "status": "not-eligible", "historical_status": historical_status}
        result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return result

    runner = repo / "historical-eval/tools/run-repair-loop.py"
    command = [
        sys.executable,
        str(runner),
        str(ranking),
        str(groups),
        str(rank),
        "-o",
        str(target / "run.json"),
        "--repo",
        str(repo),
        "--provider",
        provider,
        "--model",
        model,
        "--effort",
        effort,
        "--strategy",
        strategy,
        "--max-attempts",
        str(max_attempts),
        "--timeout",
        str(timeout),
        "--eval-timeout",
        str(eval_timeout),
    ]
    started = time.monotonic()
    completed = subprocess.run(
        command,
        cwd=repo,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    (target / "scheduler.log").write_text(completed.stdout, encoding="utf-8")
    run_result: dict[str, Any] = {}
    run_path = target / "run.json"
    if run_path.exists():
        try:
            run_result = json.loads(run_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            run_result = {}

    attempts = run_result.get("attempts", [])
    final_attempt = attempts[-1] if attempts else {}
    public_status = final_attempt.get("evaluation", {}).get("status") or final_attempt.get("status")
    result: dict[str, Any] = {
        **config,
        "config_hash": config_hash,
        "historical_status": historical_status,
        "model_status": run_result.get("final_status", "runner-error" if completed.returncode else "unknown"),
        "public_status": public_status,
        "attempts": len(attempts),
        "seconds": round(time.monotonic() - started, 3),
        "returncode": completed.returncode,
        "run": str(run_path),
        "scheduler_log": str(target / "scheduler.log"),
    }

    if rank in rescue_ranks and rescue_evaluator and rescue_private_root and attempts:
        patch = attempts[-1].get("patch")
        if patch and Path(patch).exists():
            rescue_out = rescue_private_root / f"rank-{rank:03d}-rescue.json"
            rescue_cmd = [sys.executable, str(rescue_evaluator), str(rank), patch, str(rescue_out), f"{provider}:{model}"]
            rescue = subprocess.run(
                rescue_cmd,
                cwd=repo,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                timeout=max(600, eval_timeout * 4),
                check=False,
            )
            (target / "rescue-evaluator.log").write_text(rescue.stdout, encoding="utf-8")
            result["rescue_result"] = str(rescue_out)
            if rescue_out.exists():
                try:
                    rescue_data = json.loads(rescue_out.read_text(encoding="utf-8"))
                    result["rescue_hidden_status"] = "rescue-pass" if rescue_data.get("after", {}).get("hidden", {}).get("returncode") == 0 else "rescue-fail"
                    result["rescue_historical_status"] = rescue_data.get("after", {}).get("historical", {}).get("returncode")
                except json.JSONDecodeError:
                    result["rescue_hidden_status"] = "rescue-evaluator-error"
            else:
                result["rescue_hidden_status"] = "rescue-evaluator-error"

    result_path.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return result


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("groups", type=Path)
    parser.add_argument("sandbox", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--provider", choices=("codex", "zcode"), required=True)
    parser.add_argument("--model", required=True)
    parser.add_argument("--effort", default="medium")
    parser.add_argument("--strategy", choices=("oneshot", "repair-loop"), default="oneshot")
    parser.add_argument("--max-attempts", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=900)
    parser.add_argument("--eval-timeout", type=int, default=180)
    parser.add_argument("--max-workers", type=int, default=4)
    parser.add_argument("--ranks", help="comma-separated ranks; default is all eligible ranks")
    parser.add_argument("--rescue-ranks", default="19,34")
    parser.add_argument("--rescue-evaluator", type=Path)
    parser.add_argument("--rescue-private-root", type=Path)
    args = parser.parse_args()

    repo = args.repo.resolve()
    ranking = args.ranking.resolve()
    groups = args.groups.resolve()
    sandbox_rows = read_jsonl(args.sandbox)
    sandbox = {str(row["group"]): row for row in sandbox_rows}
    rescue_ranks = {int(item) for item in args.rescue_ranks.split(",") if item.strip()}
    rows = read_jsonl(ranking)
    if args.ranks:
        selected = {int(item) for item in args.ranks.split(",") if item.strip()}
        rows = [row for row in rows if int(row["rank"]) in selected]
    else:
        rows = [row for row in rows if sandbox.get(str(row["group"]), {}).get("status") == "verified" or int(row["rank"]) in rescue_ranks]
    rows.sort(key=lambda row: int(row["rank"]))

    output_path = args.output.resolve()
    output_root = output_path.parent / (output_path.stem + ".runs")
    output_root.mkdir(parents=True, exist_ok=True)
    rescue_private_root = args.rescue_private_root.resolve() if args.rescue_private_root else None
    if rescue_private_root:
        rescue_private_root.mkdir(parents=True, exist_ok=True)
    jobs = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.max_workers)) as pool:
        for row in rows:
            jobs.append(
                pool.submit(
                    run_one,
                    repo,
                    ranking,
                    groups,
                    sandbox,
                    row,
                    args.provider,
                    args.model,
                    args.effort,
                    args.strategy,
                    args.max_attempts,
                    args.timeout,
                    args.eval_timeout,
                    output_root,
                    rescue_ranks,
                    args.rescue_evaluator.resolve() if args.rescue_evaluator else None,
                    rescue_private_root,
                )
            )
        results = [job.result() for job in jobs]

    results.sort(key=lambda item: int(item["rank"]))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text("\n".join(json.dumps(item, ensure_ascii=False, sort_keys=True) for item in results) + "\n", encoding="utf-8")
    counts: dict[str, int] = {}
    for item in results:
        status = item.get("rescue_hidden_status") if item.get("rank") in rescue_ranks and item.get("rescue_hidden_status") else item.get("model_status", "unknown")
        counts[status] = counts.get(status, 0) + 1
    print(json.dumps({"count": len(results), "statuses": counts, "output": str(output_path), "runs": str(output_root)}, ensure_ascii=False, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
