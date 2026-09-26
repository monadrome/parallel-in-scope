from __future__ import annotations

import dataclasses
import time
from pathlib import Path
from typing import List, Optional

from . import agents, gates as gates_module, schemas, workspace
from .issues import find_marker, format_comments, strip_markers, trusted_view
from .policy import valid_title
from .proc import git


@dataclasses.dataclass
class Run:
    config: object
    gh: object
    issue: object
    run_id: str
    directory: Path
    branch: str
    java_home: str
    issue_type: str
    base_sha: str = ""
    session: str = ""
    spent: float = 0.0
    rounds: int = 0
    pr: int = 0
    pushed_sha: str = ""
    review_count: int = 0
    claim_sha: str = ""
    deadline: float = 0.0
    report: dict = dataclasses.field(default_factory=dict)
    gates: list = dataclasses.field(default_factory=list)
    pit: dict = dataclasses.field(default_factory=dict)
    human_paths: list = dataclasses.field(default_factory=list)
    unresolved: list = dataclasses.field(default_factory=list)
    history: list = dataclasses.field(default_factory=list)

    @property
    def clone(self) -> Path:
        return self.directory / "clone"

    @property
    def logs(self) -> Path:
        return self.directory / "logs"


@dataclasses.dataclass
class Outcome:
    state: str
    reason: str = ""
    sha: str = ""


def values(run: Run, **extra) -> dict:
    view = trusted_view(run.issue, run.gh.login)
    external = not run.issue.trusted_author
    marker = find_marker(run.issue, "triage", run.gh.login)
    return {
        "number": run.issue.number,
        "title": view["title"] or "(withheld: external author)",
        "body": view["body"] or "(withheld: external author; the maintainer comments define the task)",
        "comments": format_comments(view["comments"]),
        "plan": strip_markers(marker[0].body) if marker else "(none)",
        "trust_note": "The issue author is outside the maintainer team; only maintainer comments are shown." if external else "",
        "branch": run.branch,
        "base_branch": run.config.base_branch,
        "base_sha": run.base_sha,
        "java_version": run.config.java_version,
        **extra,
    }


def charge(run: Run, result) -> None:
    run.spent += result.cost_usd
    run.session = result.session_id or run.session


MIN_CALL_BUDGET_USD = 1.0
DEADLINE_MARGIN_HOURS = 3


def run_deadline(config) -> float:
    return time.time() + max(config.stale_claim_hours - DEADLINE_MARGIN_HOURS, 1) * 3600


def overdue(run: Run) -> Optional[Outcome]:
    if run.deadline and time.time() >= run.deadline:
        return Outcome("blocked", "the run reached its wall-clock deadline, which keeps it inside the claim's lifetime")
    return None


def call_budget(run: Run, per_call: float) -> float:
    return min(per_call, run.config.run_budget_usd - run.spent)


def over_budget(run: Run, per_call: float = MIN_CALL_BUDGET_USD) -> Optional[Outcome]:
    if call_budget(run, per_call) < MIN_CALL_BUDGET_USD:
        return Outcome("blocked", f"run budget exhausted (${run.spent:.2f} of ${run.config.run_budget_usd:.2f})")
    return None


def implement(run: Run) -> Optional[Outcome]:
    stop = over_budget(run, run.config.implement_budget_usd)
    if stop:
        return stop
    prompt = agents.render("implement", values(run))
    result = agents.claude(run.config, prompt, schemas.REPORT, run.clone, run.logs / "implement.log",
                           call_budget(run, run.config.implement_budget_usd), run.config.implement_timeout_min,
                           read_only=False, java_home=run.java_home)
    charge(run, result)
    return accept_report(run, result)


def accept_report(run: Run, result) -> Optional[Outcome]:
    if not result.ok:
        return Outcome("blocked", f"agent session failed: {result.error}")
    report = result.output
    status = report.get("status")
    if status == "needs-decision":
        return Outcome("needs-decision", report.get("open_question") or "the agent asked for a maintainer decision")
    if status != "done":
        return Outcome("blocked", report.get("open_question") or f"the agent reported {status}")
    run.report = report
    return None


def fix(run: Run, kind: str, details: str) -> Optional[Outcome]:
    stop = overdue(run) or over_budget(run, run.config.fix_budget_usd)
    if stop:
        return stop
    run.rounds += 1
    prompt = agents.render("fix", values(run, kind=kind, details=details))
    if not run.session:
        prompt = agents.render("implement", values(run)) + "\n\n" + prompt
    result = agents.claude(run.config, prompt, schemas.REPORT, run.clone, run.logs / f"fix-{run.rounds}-{kind}.log",
                           call_budget(run, run.config.fix_budget_usd), run.config.fix_timeout_min, read_only=False,
                           resume=run.session or None, java_home=run.java_home)
    charge(run, result)
    return accept_report(run, result)


def check_gates(run: Run, check_pit: bool = True) -> list:
    run.gates, pit, run.human_paths = gates_module.run_all(
        run.clone, run.base_sha, run.issue_type, run.directory / "scratch", run.logs, run.java_home,
        run.config.verify_timeout_min, check_pit)
    run.pit = pit or run.pit
    if not valid_title(run.report.get("title") or ""):
        run.gates.append(gates_module.Gate("title", "fail", f"report title {run.report.get('title')!r} is not a lowercase Conventional Commit"))
    return gates_module.failures(run.gates)


def local_gates(run: Run) -> Optional[Outcome]:
    for attempt in range(run.config.gate_rounds + 1):
        failed = check_gates(run)
        if not failed:
            return hold_for_maintainer(run)
        if any(g.name in gates_module.TERMINAL_GATES for g in failed):
            return Outcome("blocked", failed[0].detail)
        if attempt == run.config.gate_rounds:
            break
        details = "\n\n".join(f"Gate `{g.name}` failed:\n{g.detail}" for g in failed)
        stop = fix(run, "local gate", details)
        if stop:
            return stop
    return Outcome("blocked", "local gates still fail: " + "; ".join(f"{g.name}: {g.detail[:200]}" for g in gates_module.failures(run.gates)))


def hold_for_maintainer(run: Run) -> Optional[Outcome]:
    held = [g for g in run.gates if g.status == "hold"]
    if not held:
        return None
    patch = workspace.export_patch(run.clone, run.base_sha, run.directory / "held.patch")
    return Outcome("needs-decision", f"{held[0].detail}. The verified change is saved as `{patch.name}` in run "
                                     f"`{run.run_id}` on the runner host; apply and push it by hand.")


def head(run: Run) -> str:
    return git(run.clone, "rev-parse", "HEAD")


def gate_lines(run: Run) -> List[str]:
    return [f"gate {g.name}: {g.status}" for g in run.gates]
