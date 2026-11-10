#!/usr/bin/env python3
"""Run one independent Codex repair session for a ranked group."""
from __future__ import annotations
import argparse
import difflib
import json
import subprocess
import tempfile
from pathlib import Path


def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], text=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("ranking", type=Path)
    parser.add_argument("groups", type=Path)
    parser.add_argument("rank", type=int)
    parser.add_argument("-o", "--output", type=Path, required=True)
    parser.add_argument("--repo", type=Path, default=Path.cwd())
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--reasoning-effort", default="medium")
    parser.add_argument(
        "--ephemeral",
        action="store_true",
        help="opt out of persistent Codex sessions for this run",
    )
    args = parser.parse_args()
    ranking = [json.loads(line) for line in args.ranking.read_text(encoding="utf-8").splitlines()]
    row = next(row for row in ranking if row["rank"] == args.rank)
    groups = {json.loads(line)["group"]: json.loads(line) for line in args.groups.read_text(encoding="utf-8").splitlines()}
    group = groups[row["group"]]
    commit = str(row["representative"])
    parent = git("rev-parse", f"{commit}^").strip()
    source_files = [path for path in group["production_files"] if Path(path).suffix in {".java", ".kt", ".py", ".ts", ".js", ".go", ".rs"} and not path.endswith("package-info.java")]
    temp = Path(tempfile.mkdtemp(prefix=f"codex-group-{args.rank:03d}-"))
    originals: dict[str, str] = {}
    for path in source_files:
        try:
            text = git("show", f"{parent}:{path}")
        except subprocess.CalledProcessError:
            continue
        originals[path] = text
        target = temp / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")
    (temp / "task.md").write_text(
        "请检查提供的 Java 源码，找出潜在的行为缺陷并修复。保持现有 API、Java 8 兼容性和线程安全语义；只修改生产源码，不查看 Git 历史、测试或其他答案文件。完成后直接修改工作区。\n",
        encoding="utf-8",
    )
    prompt = "Inspect task.md and the provided production source files only. Repair the latent behavioral defect directly in the workspace. Do not inspect git history, tests, oracle files, or commit metadata. Use only safe local compilation if useful."
    log_path = args.output.with_suffix(".session.log")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    command = ["codex", "exec", "--skip-git-repo-check", "-m", args.model, "-c", f"model_reasoning_effort={args.reasoning_effort}", "-s", "workspace-write", "-C", str(temp), prompt]
    if args.ephemeral:
        command.insert(2, "--ephemeral")
    try:
        completed = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=args.timeout, check=False)
        log_path.write_text(completed.stdout, encoding="utf-8")
        model_returncode = completed.returncode
    except subprocess.TimeoutExpired as exc:
        log_path.write_text((exc.stdout or "") if isinstance(exc.stdout, str) else "", encoding="utf-8")
        model_returncode = 124
    patch_parts = []
    for path, original in originals.items():
        modified_path = temp / path
        modified = modified_path.read_text(encoding="utf-8") if modified_path.exists() else original
        patch_parts.extend(difflib.unified_diff(original.splitlines(True), modified.splitlines(True), fromfile=f"a/{path}", tofile=f"b/{path}"))
    patch_path = args.output.with_suffix(".patch")
    patch_path.write_text("".join(patch_parts), encoding="utf-8")
    result = {"rank": args.rank, "group": row["group"], "representative": commit, "model": args.model, "reasoning_effort": args.reasoning_effort, "agent": "codex", "session_persistence": "ephemeral" if args.ephemeral else "persistent", "model_returncode": model_returncode, "patch": str(patch_path), "session_log": str(log_path), "workspace": str(temp), "status": "patch-ready" if patch_parts else "no-change"}
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))

if __name__ == "__main__":
    main()
