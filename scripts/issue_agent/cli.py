"""Optional issue -> triage -> agent runner for this repository.

The contract it implements is design/issue-automation.md. Local settings live in
~/.issue-agent/config.env as ISSUE_AGENT_<FIELD>=value lines (see config.Config).
"""

from __future__ import annotations

import argparse
import contextlib
import fcntl
import sys

from . import config as config_module, labels, policy, schedule, triage, work, workspace
from .github import GitHub
from .issues import annotate
from .proc import log


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(prog="issue-agent", description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = root.add_subparsers(dest="command", required=True)
    commands.add_parser("status", help="show the effective configuration and the agent queue")
    commands.add_parser("labels", help="create or update the labels the runner uses")
    triage_cmd = commands.add_parser("triage", help="triage new or changed open issues")
    triage_cmd.add_argument("--issue", type=int, action="append", help="triage only this issue (repeatable)")
    triage_cmd.add_argument("--force", action="store_true", help="re-triage even when the issue is unchanged")
    triage_cmd.add_argument("--dry-run", action="store_true", help="print the verdict without touching GitHub")
    work_cmd = commands.add_parser("work", help="run the agent pipeline on one issue")
    work_cmd.add_argument("--issue", type=int, help="work this issue; naming it is the authorization")
    work_cmd.add_argument("--dry-run", action="store_true", help="report which issue would be taken")
    commands.add_parser("tick", help="refresh the base clone, triage, then work at most one issue (the scheduled entry point)")
    schedule_cmd = commands.add_parser("schedule", help="manage the macOS launchd job that runs tick")
    schedule_cmd.add_argument("action", choices=("install", "uninstall", "show"))
    schedule_cmd.add_argument("--interval", type=int, default=60, help="minutes between ticks (default 60)")
    return root


@contextlib.contextmanager
def runner_lock(config):
    config.state_dir.mkdir(parents=True, exist_ok=True)
    with config.lock_path.open("a") as handle:
        try:
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            yield False
            return
        try:
            yield True
        finally:
            fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


def status(config, gh) -> None:
    try:
        model = config_module.resolve_claude_model(config)
    except RuntimeError as error:
        model = f"(unset: {error})"
    print(f"repo {config.repo}, dev line {config.base_branch}, state {config.state_dir}")
    print(f"claude {config.claude_bin} model {model}; codex {config.codex_bin} model {config.codex_model or '(codex default)'}")
    print(f"JDK {config_module.resolve_java_home(config) or '(not found)'}; auto priorities {sorted(config.auto_priority_set)}; "
          f"pending-review limit {config.max_pending_reviews}; run budget ${config.run_budget_usd:.0f}")
    issues = [annotate(issue, gh.permission, gh.login) for issue in gh.open_issues()]
    queued = {c.number for c in policy.select_candidates(issues, config.auto_priority_set)}
    for issue in issues:
        mark = "queued" if issue.number in queued else ""
        tags = ",".join(sorted(set(issue.labels) & (labels.VETOES | {labels.READY, labels.ELIGIBLE}))) or "-"
        print(f"#{issue.number:<5} {mark:<7} {tags:<40} {issue.title[:70]}")


def main(argv=None) -> int:
    args = parser().parse_args(argv)
    config = config_module.load()
    gh = GitHub(config)
    if args.command == "status":
        status(config, gh)
        return 0
    if args.command == "labels":
        print("updated: " + (", ".join(gh.ensure_labels(labels.DEFINITIONS)) or "nothing"))
        return 0
    if args.command == "schedule":
        actions = {
            "install": lambda: f"installed {schedule.install(config, args.interval)}",
            "uninstall": lambda: "removed" if schedule.uninstall(config) else "not installed",
            "show": lambda: schedule.show(config),
        }
        print(actions[args.action]())
        return 0
    with runner_lock(config) as acquired:
        if not acquired:
            log(f"{args.command}: another runner holds {config.lock_path}")
            return 0 if args.command == "tick" else 1
        base = workspace.refresh_base(config)
        if args.command == "triage":
            triage.triage_all(config, gh, base, args.issue, args.dry_run, args.force)
        elif args.command == "work":
            work.work(config, gh, args.issue, args.dry_run)
        else:
            gh.ensure_labels(labels.DEFINITIONS)
            triage.triage_all(config, gh, base)
            work.work(config, gh)
    return 0


if __name__ == "__main__":
    sys.exit(main())
