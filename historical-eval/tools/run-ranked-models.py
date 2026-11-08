#!/usr/bin/env python3
"""Run independent Codex repairs and historical sandbox evaluations in rank order."""
from __future__ import annotations
import argparse
import json
import subprocess
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("groups", type=Path)
    parser.add_argument("sandbox_results", type=Path)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--start-rank", type=int, default=1)
    parser.add_argument("--limit", type=int)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    args = parser.parse_args()
    ranking = [json.loads(line) for line in args.ranking.read_text(encoding="utf-8").splitlines()]
    sandbox = {json.loads(line)["group"]: json.loads(line) for line in args.sandbox_results.read_text(encoding="utf-8").splitlines()}
    selected = [row for row in ranking if row["rank"] >= args.start_rank]
    if args.limit:
        selected = selected[: args.limit]
    results = []
    for row in selected:
        group = row["group"]
        target = args.output.parent / "all" / f"{row['rank']:03d}-{group}"
        target.mkdir(parents=True, exist_ok=True)
        result_path = target / "result.json"
        if result_path.exists():
            results.append(json.loads(result_path.read_text()))
            print(f"reuse rank={row['rank']} group={group}", flush=True)
            continue
        existing_eval = target / "evaluation.json"
        existing_session = target / "session.json"
        if existing_eval.exists() and existing_session.exists():
            evaluation = json.loads(existing_eval.read_text())
            session = json.loads(existing_session.read_text())
            result = {"rank": row["rank"], "group": group, "model": "gpt-5.6-terra", "reasoning_effort": "medium", "agent": "codex", "historical_sandbox_status": sandbox.get(group, {}).get("status", "not-run"), "model_session_returncode": session.get("model_returncode"), "model_evaluation_status": evaluation.get("status"), "patch": str(target / "candidate.patch"), "evaluation": str(existing_eval)}
            result_path.write_text(json.dumps(result, indent=2) + "\n")
            results.append(result)
            print(f"reuse rank={row['rank']} group={group}", flush=True)
            continue
        historical_status = sandbox.get(group, {}).get("status", "not-run")
        if historical_status != "verified":
            result = {"rank": row["rank"], "group": group, "model": "gpt-5.6-terra", "reasoning_effort": "medium", "agent": "codex", "status": "not-eligible", "historical_sandbox_status": historical_status}
            result_path.write_text(json.dumps(result, indent=2) + "\n")
            results.append(result)
            print(f"skip rank={row['rank']} group={group} historical={historical_status}", flush=True)
            continue
        session_path = target / "session.json"
        run = subprocess.run(["python3", str(args.repo / "historical-eval/tools/run-codex-group.py"), str(args.ranking), str(args.groups), str(row["rank"]), "-o", str(session_path), "--repo", str(args.repo)], cwd=args.repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
        patch = session_path.with_suffix(".patch")
        if patch.exists():
            eval_path = target / "evaluation.json"
            subprocess.run(["python3", str(args.repo / "historical-eval/tools/evaluate-group-patch.py"), str(args.ranking), group, str(patch), "-o", str(eval_path), "--repo", str(args.repo)], cwd=args.repo, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, check=False)
            evaluation = json.loads(eval_path.read_text()) if eval_path.exists() else {"status": "evaluation-missing"}
        else:
            evaluation = {"status": "no-patch"}
        result = {"rank": row["rank"], "group": group, "model": "gpt-5.6-terra", "reasoning_effort": "medium", "agent": "codex", "historical_sandbox_status": historical_status, "model_session_returncode": json.loads(session_path.read_text()).get("model_returncode") if session_path.exists() else None, "model_evaluation_status": evaluation.get("status"), "patch": str(patch), "evaluation": str(target / "evaluation.json")}
        result_path.write_text(json.dumps(result, indent=2) + "\n")
        results.append(result)
        print(json.dumps(result, sort_keys=True), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(result, ensure_ascii=False, sort_keys=True) for result in results) + ("\n" if results else ""), encoding="utf-8")
    counts = {}
    for result in results:
        status = result.get("model_evaluation_status", result.get("status"))
        counts[status] = counts.get(status, 0) + 1
    print(json.dumps({"processed": len(results), "statuses": counts, "output": str(args.output)}, sort_keys=True))

if __name__ == "__main__":
    main()
