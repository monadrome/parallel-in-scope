from __future__ import annotations

from typing import Iterable, Mapping, Sequence

from . import labels
from .issues import render_marker, sanitize

ATTRIBUTION = "🤖 Generated with [Claude Code](https://claude.com/claude-code)"
CO_AUTHOR = "Co-Authored-By: Claude <noreply@anthropic.com>"
ROUTE_TEXT = {
    "agent": "agent: fit for unattended work",
    "human": "human: waits for a maintainer",
}
REASON_TEXT = {
    "direction": "an open design choice needs a maintainer decision",
    "public-api": "it changes a public API or documented contract whose shape is not pinned yet",
    "sensitive": "it touches dependencies, deletions, release or credential handling, or a primitive AGENTS.md reserves for confirmation",
    "needs-info": "the report lacks what a fix needs",
    "resolved": "the current code already resolves or supersedes it",
    "duplicate": "another issue covers it",
    "external": "the author is outside the maintainer team, so the agent does not act on this text",
}


def cell(text: str) -> str:
    return sanitize(text, 300).replace("|", "\\|").replace("\n", " ") or "-"


def bullets(items: Iterable[str]) -> str:
    lines = [f"- {sanitize(item, 600)}" for item in items if item]
    return "\n".join(lines) if lines else "- (none)"


def triage_comment(verdict, marker: Mapping, auto_priorities: Iterable[str]) -> str:
    lines = [
        "### Automated triage",
        "",
        "| Type | Priority | Size | Route |",
        "|---|---|---|---|",
        f"| {verdict.type} | {verdict.priority} | {verdict.size} | {ROUTE_TEXT[verdict.route]} |",
        "",
    ]
    if verdict.route == "human":
        lines.append(f"Routed to a maintainer because {REASON_TEXT[verdict.reason]}.")
    elif verdict.priority in set(auto_priorities):
        lines.append(f"The agent runner picks this up on its next pass. Add `{labels.NEEDS_DECISION}` to stop it.")
    else:
        lines.append(f"Add `{labels.READY}` to queue it for the agent runner.")
    if verdict.summary:
        lines += ["", sanitize(verdict.summary, 2000)]
    if verdict.plan:
        lines += ["", "**Plan**", "", sanitize(verdict.plan, 5000)]
    if verdict.areas:
        lines += ["", f"Likely touches: {sanitize(verdict.areas, 1000)}"]
    if verdict.duplicate_of:
        lines += ["", f"Possible duplicate of #{verdict.duplicate_of}."]
    lines += [
        "",
        f"Labels set here are the runner's suggestion: change or remove any of them and triage leaves that choice alone. "
        f"`{labels.READY}` authorizes the runner; `{labels.NEEDS_DECISION}` stops it.",
        "",
        render_marker("triage", marker),
    ]
    return "\n".join(lines)


def run_comment(run_id: str, headline: str, details: Sequence[str], marker: Mapping) -> str:
    lines = [f"### Agent run `{run_id}`", "", sanitize(headline, 600), ""]
    lines += [f"- {sanitize(detail, 600)}" for detail in details if detail]
    lines += ["", render_marker("run", marker)]
    return "\n".join(lines)


def pr_body(number: int, report: Mapping, gates: Sequence, pit: Mapping) -> str:
    rows = [f"| {cell(item)} | agent |" for item in report.get("verification") or []]
    rows += [f"| runner gate `{g.name}` | {g.status}{': ' + cell(g.detail) if g.detail else ''} |" for g in gates]
    survivors = pit.get("survivors") or []
    pit_line = f"{pit.get('killed', 0)} killed, {len(survivors)} surviving" if pit else "not applicable"
    breaking = bool(report.get("breaking"))
    link = "Closes" if report.get("completes_issue") else "Refs"
    return "\n".join([
        "## Summary",
        "",
        sanitize(report.get("summary") or "", 6000),
        "",
        "Contract items added or changed:",
        "",
        bullets(report.get("contract_items") or []),
        "",
        "## Related issue",
        "",
        f"{link} #{number}",
        "",
        "## Verification",
        "",
        "| Command | Result |",
        "|---|---|",
        *rows,
        "",
        f"Mutation coverage: {pit_line}. Agent classification: {sanitize(report.get('pit') or 'none given', 2000)}",
        "",
        "## Breaking changes",
        "",
        f"- [{' ' if breaking else 'x'}] No breaking changes",
        f"- [{'x' if breaking else ' '}] Breaking changes, rationale in the summary; migration notes updated",
        "",
        ATTRIBUTION,
    ])


def squash_body(number: int, report: Mapping) -> str:
    link = "Closes" if report.get("completes_issue") else "Refs"
    parts = [sanitize(report.get("summary") or "", 3000)]
    if report.get("contract_items"):
        parts.append("Contract items:\n" + bullets(report["contract_items"]))
    parts += [f"{link} #{number}", CO_AUTHOR]
    return "\n\n".join(parts)


def review_comment(round_number: int, head: str, review: Mapping) -> str:
    findings = review.get("findings") or []
    lines = [f"### Independent review, round {round_number} (`{head[:12]}`)", "", f"Verdict: **{review.get('verdict')}**", ""]
    for finding in findings:
        lines.append(f"- **{finding.get('severity')}** `{cell(finding.get('file') or '')}`: {sanitize(finding.get('summary') or '', 800)}")
        if finding.get("evidence"):
            lines.append(f"  - Evidence: {sanitize(finding['evidence'], 1200)}")
    if review.get("notes"):
        lines += ["", sanitize(review["notes"], 1500)]
    return "\n".join(lines)


def findings_text(findings: Sequence[Mapping]) -> str:
    blocks = []
    for index, finding in enumerate(findings, 1):
        blocks.append(
            f"{index}. [{finding.get('severity')}] {finding.get('file')}: {finding.get('summary')}\n"
            f"   Evidence: {finding.get('evidence')}\n   Suggestion: {finding.get('suggestion')}"
        )
    return "\n".join(blocks) or "(none)"
