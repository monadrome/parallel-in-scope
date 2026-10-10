from __future__ import annotations

import datetime
import os
import socket
import uuid
from typing import List, Optional

from . import config as config_module, labels, landing, policy
from .issues import Issue, annotate, trusted_view
from .outcomes import claim_note, finish, housekeeping, machine_id, post_run
from .pipeline import Outcome, Run, run_deadline
from .proc import git, log


def new_run_id() -> str:
    return datetime.datetime.now().strftime("%Y%m%d%H%M%S") + "-" + uuid.uuid4().hex[:6]


def issue_type(issue: Issue) -> str:
    return "bug" if labels.TYPE_LABELS["bug"] in issue.labels else "other"


def refusal(issue: Issue, runner_login: str) -> Optional[str]:
    if issue.state != "OPEN":
        return f"#{issue.number} is {issue.state.lower()}"
    vetoes = sorted(set(issue.labels) & labels.VETOES)
    if vetoes:
        return f"#{issue.number} carries {', '.join(vetoes)}"
    if not issue.trusted_author and not trusted_view(issue, runner_login)["comments"]:
        return f"#{issue.number} comes from outside the maintainer team and no maintainer comment states the task"
    return None


def annotated(gh) -> List[Issue]:
    return [annotate(issue, gh.permission, gh.login) for issue in gh.open_issues()]


def work(config, gh, number: Optional[int] = None, dry_run: bool = False) -> Optional[Outcome]:
    issues = annotated(gh)
    if not dry_run and housekeeping(config, gh, issues):
        issues = annotated(gh)
    if number is None:
        pending = [i for i in issues if labels.REVIEW in i.labels]
        if len(pending) >= config.max_pending_reviews:
            log(f"work: {len(pending)} agent PRs wait for review (limit {config.max_pending_reviews}); not starting another")
            return None
        candidates = policy.select_candidates(issues, config.auto_priority_set)
        if not candidates:
            log("work: no issue is queued for the agent")
            return None
        number = candidates[0].number
    issue = annotate(gh.issue(number), gh.permission, gh.login)
    reason = refusal(issue, gh.login)
    if reason:
        log(f"work: refusing, {reason}")
        return None
    if dry_run:
        log(f"work: would take #{number} ({issue.title})")
        return None
    return work_issue(config, gh, issue)


def work_issue(config, gh, issue: Issue) -> Optional[Outcome]:
    java_home = config_module.resolve_java_home(config)
    if not java_home:
        raise RuntimeError(f"no JDK {config.java_version}; set ISSUE_AGENT_JAVA_HOME")
    run_id, machine = new_run_id(), machine_id(config)
    tree = git(config.base_clone, "rev-parse", "HEAD^{tree}")
    claim = gh.create_claim(issue.number, tree, claim_note(machine, run_id))
    if not claim:
        log(f"work: #{issue.number} is claimed by another runner ({gh.claim_ref(issue.number)}); it expires after "
            f"{config.stale_claim_hours} hours")
        return None
    branch = policy.branch_name(issue.number, issue.title, run_id)
    run = Run(config, gh, issue, run_id, config.runs_dir / run_id, branch, java_home, issue_type(issue),
              claim_sha=claim, deadline=run_deadline(config))
    try:
        post_run(gh, issue.number, run_id, "claimed", [f"Branch `{branch}` from `{config.base_branch}`."],
                 {"machine": machine, "host": socket.gethostname(), "pid": os.getpid(), "branch": branch})
        gh.edit_labels(issue.number, add=[labels.WORKING])
        outcome = landing.execute(run)
    except Exception as error:
        outcome = Outcome("blocked", f"runner error: {error}")
    try:
        finish(config, gh, run, outcome)
    except Exception as error:
        log(f"#{issue.number}: recording the outcome failed; the next tick recovers it from the run directory: {error}")
    return outcome
