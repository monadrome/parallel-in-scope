from __future__ import annotations

import time
from typing import List, Optional

from . import agents, render, schemas, workspace
from .github import failed_checks
from .outcomes import machine_id, post_run
from .pipeline import Outcome, Run, check_gates, fix, head, implement, local_gates, overdue, values
from .policy import BRANCH_PREFIX
from .proc import CommandError, git, log

POLL_SECONDS = 30


def execute(run: Run) -> Outcome:
    run.logs.mkdir(parents=True, exist_ok=True)
    run.base_sha = workspace.prepare_clone(run.config, run.clone, run.branch)
    log(f"#{run.issue.number}: run {run.run_id} on {run.branch} from {run.base_sha[:12]}")
    for step in (implement, local_gates, open_pr, review_loop, ci_loop, land):
        outcome = overdue(run) or step(run)
        if outcome is not None:
            return outcome
    return Outcome("blocked", "the pipeline ended without an outcome")


def open_pr(run: Run) -> Optional[Outcome]:
    for number, branch in run.gh.open_prs_with_prefix(f"{BRANCH_PREFIX}{run.issue.number}-"):
        run.gh.close_pr(number, f"Superseded by agent run `{run.run_id}`.")
    run.pushed_sha = workspace.push(run.config, run.clone, run.branch, "")
    run.pr = run.gh.create_pr(run.branch, run.report["title"], render.pr_body(run.issue.number, run.report, run.gates, run.pit))
    log(f"#{run.issue.number}: opened PR #{run.pr}")
    post_run(run.gh, run.issue.number, run.run_id, "pr-open", [f"PR #{run.pr} on `{run.branch}`."],
             {"machine": machine_id(run.config), "branch": run.branch, "pr": run.pr})
    return None


def republish(run: Run) -> None:
    run.pushed_sha = workspace.push(run.config, run.clone, run.branch, run.pushed_sha)
    run.gh.pr_edit_body(run.pr, render.pr_body(run.issue.number, run.report, run.gates, run.pit))


def review_loop(run: Run) -> Optional[Outcome]:
    while True:
        run.review_count += 1
        review = independent_review(run)
        if review is None:
            run.unresolved = ["the independent reviewer returned no verdict"]
            return None
        run.gh.pr_comment(run.pr, render.review_comment(run.review_count, run.pushed_sha, review))
        blocking = schemas.blocking_findings(review)
        run.history.append(review)
        if not blocking:
            run.unresolved = []
            return None
        if run.review_count >= run.config.review_rounds:
            return Outcome("blocked", f"{len(blocking)} blocking review findings remain after {run.review_count} rounds")
        stop = fix(run, "independent review", render.findings_text(blocking)) or local_gates(run)
        if stop:
            return stop
        republish(run)


STRICT_JSON = ("\n\nYour previous answer could not be parsed. Reply with only the JSON object that the output schema "
               "describes: no prose, no markdown fences.")


def independent_review(run: Run) -> Optional[dict]:
    diffstat = git(run.clone, "diff", "--stat", f"{run.base_sha}..HEAD")
    prompt = agents.render("review", values(
        run,
        summary=run.report.get("summary") or "",
        contract_items=agents.joined(run.report.get("contract_items") or []),
        pit=pit_text(run),
        diffstat=diffstat,
        previous=previous_rounds(run),
    ))
    for attempt in range(2):
        result = agents.codex_review(run.config, prompt + (STRICT_JSON if attempt else ""), schemas.REVIEW, run.clone,
                                     run.logs / f"review-{run.review_count}-{attempt}.log", run.config.review_timeout_min)
        review = schemas.normalize_review(result.output) if result.ok else None
        if review is not None:
            return review
    return None


def pit_text(run: Run) -> str:
    survivors = run.pit.get("survivors") or []
    counted = f"{run.pit.get('killed', 0)} killed, {len(survivors)} surviving" if run.pit else "not run"
    listing = "\n".join(f"- {s}" for s in survivors[:40])
    return f"Runner count: {counted}\n{listing}\n\nAgent classification: {run.report.get('pit') or '(none)'}"


def previous_rounds(run: Run) -> str:
    if not run.history:
        return ""
    lines = ["## Previous rounds", "", "Check that each earlier finding is actually resolved, then look beyond them.", ""]
    for index, review in enumerate(run.history, 1):
        lines.append(f"Round {index}:\n{render.findings_text(review.get('findings') or [])}")
    dispositions = run.report.get("dispositions") or []
    if dispositions:
        lines.append("Implementer dispositions:\n" + "\n".join(f"- {d.get('disposition')}: {d.get('finding')} -> {d.get('action')}" for d in dispositions))
    return "\n\n".join(lines)


def wait_ci(run: Run, sha: str) -> tuple:
    deadline = time.monotonic() + run.config.ci_timeout_min * 60
    previous_names: Optional[frozenset] = None
    while time.monotonic() < deadline:
        time.sleep(POLL_SECONDS)
        checks = run.gh.commit_checks(sha)
        names = frozenset(c["name"] for c in checks)
        settled = checks and all(c["bucket"] != "pending" for c in checks) and names == previous_names
        previous_names = names
        if settled:
            failed = failed_checks(checks)
            return ("red", failed) if failed else ("green", [])
    return "timeout", []


def ci_loop(run: Run) -> Optional[Outcome]:
    for attempt in range(run.config.ci_rounds + 1):
        state, failed = wait_ci(run, run.pushed_sha)
        if state == "green":
            return None
        if state == "timeout":
            return Outcome("blocked", f"CI did not settle within {run.config.ci_timeout_min} minutes")
        if attempt == run.config.ci_rounds:
            break
        details = "\n\n".join(f"Check `{c['name']}` failed. Log tail:\n{run.gh.failed_log(c['link'])}" for c in failed)
        stop = fix(run, "CI", details) or local_gates(run)
        if stop:
            return stop
        republish(run)
        stop = review_loop(run)
        if stop:
            return stop
    return Outcome("blocked", f"CI still fails after {run.config.ci_rounds} fix rounds")


def human_reasons(run: Run) -> List[str]:
    reasons = []
    if run.human_paths:
        reasons.append("touches maintainer-merge paths: " + ", ".join(run.human_paths))
    reasons += run.unresolved
    reasons += [f"gate {g.name} is inconclusive: {g.detail}" for g in run.gates if g.status == "inconclusive"]
    return reasons


def land(run: Run) -> Outcome:
    reasons = human_reasons(run)
    if reasons:
        return Outcome("review", "; ".join(reasons))
    fresh_base = workspace.rebase_onto_base(run.config, run.clone, run.base_sha)
    if fresh_base is None:
        return Outcome("review", f"rebasing onto {run.config.base_branch} conflicts; resolve and merge by hand")
    if head(run) != run.pushed_sha:
        run.base_sha = fresh_base
        failed = check_gates(run, check_pit=False)
        if failed or run.human_paths:
            detail = "; ".join(f"{g.name}: {g.detail[:200]}" for g in failed) or "the rebased diff touches maintainer-merge paths"
            return Outcome("review", f"after rebasing onto {run.config.base_branch}: {detail}")
        run.pushed_sha = workspace.push(run.config, run.clone, run.branch, run.pushed_sha)
        state, _ = wait_ci(run, run.pushed_sha)
        if state != "green":
            return Outcome("review", f"CI is {state} after rebasing onto {run.config.base_branch}")
    stop = overdue(run)
    if stop:
        return stop
    if not run.gh.holds_claim(run.issue.number, run.claim_sha):
        return Outcome("blocked", "this run no longer holds its claim tag; another runner may own the issue now")
    merged = merge(run)
    if isinstance(merged, Outcome):
        return merged
    run.gh.delete_branch(run.branch)
    log(f"#{run.issue.number}: merged PR #{run.pr} as {merged[:12]}")
    return Outcome("merged", f"PR #{run.pr}", merged)


def merge(run: Run):
    subject = f"{run.report['title']} (#{run.pr})"
    try:
        return run.gh.merge_pr(run.pr, run.pushed_sha, subject, render.squash_body(run.issue.number, run.report))
    except CommandError:
        state, sha = run.gh.pr_state(run.pr)
        if state != "MERGED":
            raise
        try:
            base = run.gh.pr_base(run.pr)
        except CommandError:
            base = ""
        if base == run.config.base_branch:
            return sha
        return Outcome("needs-decision", f"PR #{run.pr} merged as {sha[:12]} but its base could not be confirmed as {run.config.base_branch}")
