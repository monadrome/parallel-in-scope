from __future__ import annotations

import datetime
import json
import shlex
import socket
import time
import uuid
from typing import Iterable, List, Optional

from . import labels, render
from .issues import Issue, find_marker
from .proc import CommandError, log, run as run_command

CLOCK_SKEW_HOURS = 1.0
PR_READ_ATTEMPTS = 3
OUTCOME_FILE = "outcome.json"

FINAL_STATES = frozenset({"merged", "partial", "review", "needs-decision", "blocked"})
STATE_LABELS = {
    "partial": labels.NEEDS_DECISION,
    "review": labels.REVIEW,
    "needs-decision": labels.NEEDS_DECISION,
    "blocked": labels.BLOCKED,
}
HEADLINES = {
    "claimed": "Claimed by the agent runner.",
    "pr-open": "Working; the pull request is open.",
    "merged": "Merged into the dev line.",
    "review": "Passed the runner's gates; waits for a maintainer to merge.",
    "needs-decision": "Stopped: needs a maintainer decision.",
    "blocked": "Stopped. Remove `agent/blocked` to let the runner retry.",
    "partial": "Merged, but the issue is not complete; waits for a maintainer decision on the rest.",
}


def machine_id(config) -> str:
    path = config.state_dir / "machine-id"
    if not path.exists():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(uuid.uuid4().hex, encoding="utf-8")
    return path.read_text(encoding="utf-8").strip()


def claim_note(machine: str, run_id: str, now: Optional[float] = None) -> str:
    epoch = int(time.time() if now is None else now)
    return f"issue-agent claim\n\nmachine={machine}\nrun={run_id}\nhost={socket.gethostname()}\ncreated_epoch={epoch}\n"


def claim_age_hours(claim: dict, now: Optional[datetime.datetime] = None) -> Optional[float]:
    now = now or datetime.datetime.now(datetime.timezone.utc)
    epoch = claim.get("created_epoch", "")
    if epoch.isdigit():
        return (now.timestamp() - int(epoch)) / 3600
    try:
        stamp = datetime.datetime.fromisoformat(claim.get("created", "").replace("Z", "+00:00"))
    except ValueError:
        return None
    if stamp.tzinfo is None:
        return None
    return (now - stamp).total_seconds() / 3600


def note_unreadable_claim(config, number: int) -> None:
    seen_path = config.state_dir / "unreadable-claims"
    seen = set(seen_path.read_text(encoding="utf-8").split()) if seen_path.exists() else set()
    if str(number) in seen:
        return
    seen_path.write_text(" ".join(sorted(seen | {str(number)})), encoding="utf-8")
    message = f"{config.repo}#{number}: claim tag issue-agent-claim/issue-{number} has no readable owner or age; delete it by hand if no runner is working the issue"
    log(message)
    notify(config, message)


def post_run(gh, number: int, run_id: str, state: str, details: List[str], data: dict) -> None:
    marker = find_marker(gh.issue(number), "run", gh.login)
    body = render.run_comment(run_id, HEADLINES[state], details, {**data, "state": state, "run": run_id})
    gh.upsert_comment(number, body, marker[0] if marker else None)


def apply_state(config, gh, number: int, present: Iterable[str], state: str, data: dict) -> None:
    present = set(present)
    if state == "merged":
        gh.close_issue(number, "completed", f"Landed in {str(data.get('sha', ''))[:12]} on {config.base_branch} via #{data.get('pr')}.")
    target = STATE_LABELS.get(state)
    remove = sorted((present & {labels.WORKING, labels.REVIEW}) - {target})
    gh.edit_labels(number, add=[target] if target and target not in present else [], remove=remove)


def finish(config, gh, run, outcome) -> None:
    completes = bool(run.report.get("completes_issue"))
    state = "partial" if outcome.state == "merged" and not completes else outcome.state
    details = [outcome.reason, f"PR #{run.pr}" if run.pr else "", f"Spent ${run.spent:.2f} on Claude sessions.",
               f"Logs stay on the runner host under run `{run.run_id}`."]
    details += [f"gate {g.name}: {g.status}" for g in run.gates]
    data = {"pr": run.pr, "sha": outcome.sha, "completes": completes, "machine": machine_id(config),
            "host": socket.gethostname(), "branch": run.branch}
    record = run.directory / OUTCOME_FILE
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(json.dumps({"issue": run.issue.number, "machine": data["machine"], "state": state,
                                  "details": details, "data": data}), encoding="utf-8")
    if claim_status(gh, run.issue.number, run.claim_sha) is False:
        message = f"{config.repo}#{run.issue.number} agent run {run.run_id} lost its claim; left the issue to its new owner ({state}: {outcome.reason})"
        log(message)
        notify(config, message)
        return
    settle(config, gh, run.issue.number, run.run_id, set(run.issue.labels) | {labels.WORKING}, state, data, details)
    try:
        gh.release_claim(run.issue.number, run.claim_sha)
    except CommandError as error:
        log(f"#{run.issue.number}: the claim stays until the next tick: {error}")
    notify(config, f"{config.repo}#{run.issue.number} agent run {run.run_id}: {state}. {outcome.reason}")


def drafts(state: str, data: dict) -> bool:
    return state in ("blocked", "needs-decision") and bool(data.get("pr"))


def settle(config, gh, number: int, run_id: str, present: Iterable[str], state: str, data: dict,
           details: Optional[List[str]] = None, draft: Optional[bool] = None) -> None:
    if details is not None:
        post_run(gh, number, run_id, state, details, data)
    apply_state(config, gh, number, present, state, data)
    if (drafts(state, data) if draft is None else draft) and data.get("pr"):
        gh.pr_to_draft(int(data["pr"]))


def claim_status(gh, number: int, sha: str) -> Optional[bool]:
    try:
        return gh.holds_claim(number, sha)
    except CommandError:
        return None


def recorded_outcome(config, run_id: str, number: int) -> Optional[dict]:
    try:
        record = json.loads((config.runs_dir / run_id / OUTCOME_FILE).read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if not isinstance(record, dict) or record.get("issue") != number or record.get("machine") != machine_id(config):
        return None
    return record if record.get("state") in FINAL_STATES else None


def interrupted_state(gh, data: dict) -> tuple:
    if not data.get("pr"):
        return "blocked", "The runner stopped mid-run.", False
    try:
        pr_state, sha = gh.pr_state(int(data["pr"]))
    except (CommandError, ValueError) as error:
        if int(data.get("attempts", 0)) + 1 < PR_READ_ATTEMPTS:
            return None, str(error)[:200], False
        return "blocked", f"The runner stopped mid-run; PR #{data['pr']} could not be read after {PR_READ_ATTEMPTS} ticks.", False
    if pr_state == "MERGED":
        return "partial", f"The runner stopped after PR #{data['pr']} merged as {sha[:12]}; confirm whether the issue is complete.", False
    return "blocked", f"The runner stopped mid-run; PR #{data['pr']} is {pr_state.lower() or 'unknown'}.", pr_state == "OPEN"


def recover(config, gh, issue: Issue, run_id: str) -> bool:
    marker = find_marker(issue, "run", gh.login)
    data = marker[1] if marker else {}
    if data.get("run") != run_id:
        return True
    state, details, draft = data.get("state"), None, None
    if state not in FINAL_STATES:
        recorded = recorded_outcome(config, run_id, issue.number)
        if recorded:
            state, data = recorded["state"], {**data, **(recorded.get("data") or {})}
            details = list(recorded.get("details") or [])
        else:
            state, reason, confirmed_open = interrupted_state(gh, data)
            if state is None:
                attempts = int(data.get("attempts", 0)) + 1
                post_run(gh, issue.number, run_id, "pr-open", [f"PR state unreadable ({attempts}/{PR_READ_ATTEMPTS}): {reason}"],
                         {**data, "attempts": attempts})
                return False
            details, draft = [reason], confirmed_open
    settle(config, gh, issue.number, run_id, issue.labels, state, data, details, draft)
    return True


def sweep_claim(config, gh, number: int, claim: dict, issue: Optional[Issue], mine: str, now) -> bool:
    if claim.get("machine") == mine:
        if issue is not None and not recover(config, gh, issue, claim.get("run", "")):
            return True
        gh.release_claim(number, claim.get("sha", ""))
        return True
    age = claim_age_hours(claim, now)
    if age is None or age < -CLOCK_SKEW_HOURS or not claim.get("machine"):
        note_unreadable_claim(config, number)
        return False
    if age < config.stale_claim_hours:
        return False
    if issue is not None and labels.WORKING in issue.labels:
        marker = find_marker(issue, "run", gh.login)
        if marker and marker[1].get("run") == claim.get("run"):
            if not recover(config, gh, issue, claim.get("run", "")):
                return True
        else:
            settle(config, gh, number, claim.get("run", "unknown"), issue.labels, "blocked", {},
                   [f"The claim from {claim.get('host', 'another runner')} expired after {age:.0f} hours."])
    gh.release_claim(number, claim.get("sha", ""))
    return True


def sweep_issue(config, gh, issue: Issue, mine: str) -> bool:
    marker = find_marker(issue, "run", gh.login)
    data = marker[1] if marker else {}
    if labels.WORKING in issue.labels and data.get("machine") == mine:
        recover(config, gh, issue, data.get("run", ""))
        return True
    if labels.REVIEW not in issue.labels or not data.get("pr"):
        return False
    pr_state, sha = gh.pr_state(int(data["pr"]))
    if pr_state not in ("MERGED", "CLOSED"):
        return False
    state = ("merged" if data.get("completes") else "partial") if pr_state == "MERGED" else "blocked"
    reason = "A maintainer merged the PR." if pr_state == "MERGED" else "The PR was closed without merging."
    settle(config, gh, issue.number, data.get("run", "unknown"), issue.labels, state, {**data, "sha": sha}, [reason], draft=False)
    return True


def housekeeping(config, gh, issues: List[Issue], now: Optional[datetime.datetime] = None) -> bool:
    mine, changed = machine_id(config), False
    by_number = {issue.number: issue for issue in issues}
    claims = gh.claims()
    for number, claim in claims.items():
        try:
            changed = sweep_claim(config, gh, number, claim, by_number.get(number), mine, now) or changed
        except Exception as error:
            log(f"housekeeping: #{number}'s claim waits for the next tick: {error}")
    for issue in issues:
        if issue.number in claims:
            continue
        try:
            changed = sweep_issue(config, gh, issue, mine) or changed
        except Exception as error:
            log(f"housekeeping: #{issue.number} waits for the next tick: {error}")
    return changed


def notify(config, message: str) -> None:
    if not config.notify_cmd:
        return
    result = run_command([*shlex.split(config.notify_cmd), message], check=False, timeout=60)
    if result.returncode != 0:
        log(f"notify failed: {(result.stderr or result.stdout).strip()[:300]}")
