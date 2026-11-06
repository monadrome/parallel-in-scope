#!/usr/bin/env python3
"""Record and aggregate per-change process metrics for the delivery ledger.

The raw evidence a change leaves behind -- llm-review-bench run directories, refactoring-review run
directories, PIT ``mutations.xml`` -- is git-ignored and disappears with its worktree. ``record``
snapshots the numbers that matter into one tracked JSON file per delivery; ``report`` aggregates
those records, optionally together with run directories that predate the ledger and with GitHub
Actions history. See ``verification/delivery-ledger.md``.

Subcommands:

``record``
    Write ``ledger/<date>-<topic>.json`` for the commits in
    ``--base..HEAD``. Review runs and PIT reports are named explicitly; gate failures,
    interventions, and implementer usage are self-reported counts.
``report``
    Aggregate every record (and every ``--scan`` directory not already recorded) into per-change
    rows, a rule-violation ranking, gate-failure counts, and review yield. ``--ci N`` adds failed
    GitHub Actions steps from the last N runs through ``gh``.

Every snapshot keeps raw counts; unknown values stay ``null`` and are never estimated. Exit
status: 0 success; 2 the command could not run.
"""

from __future__ import annotations

import argparse
import datetime
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from collections import Counter
from pathlib import Path
from typing import Callable

SCHEMA = "process-ledger/v1"
LEDGER_DIR = Path("ledger")
LENSES_DIR = Path("refactoring-review/lenses")
BENCH_RUNS = Path(".review-benchmark/runs")
REVIEWS_DIR = Path(".agents/reviews")
BENCH_RUN_SCHEMA = "llm-review-bench/run/v1"
ROUTES = ("mechanical", "defect", "contract", "concurrency", "docs", "dependency", "release", "other")
DISPOSITIONS = ("applied", "rejected", "deferred", "review", "reverted")
# pit.md "Interpret every raw status": these four are conclusive (or reported separately, for
# NON_VIABLE); every other status -- TIMED_OUT, MEMORY_ERROR, RUN_ERROR, unknown -- is inconclusive.
PIT_CONCLUSIVE = ("KILLED", "SURVIVED", "NO_COVERAGE", "NON_VIABLE")
RULE_HEADING = re.compile(r"^### ([A-Z][0-9]{2}) (.+)$", re.MULTILINE)
TOPIC = re.compile(r"^[a-z0-9][a-z0-9-]*$")
VERSIONED = re.compile(r"^v(\d+)\.json$")


class RunError(Exception):
    """The command cannot run; reported with exit status 2."""


def run_command(argv: list[str], shown: list[str]) -> str:
    """Run ``argv`` and return its stdout; a nonzero exit raises RunError naming ``shown``."""
    result = subprocess.run(argv, capture_output=True, text=True)
    if result.returncode != 0:
        raise RunError(f"{' '.join(shown)} failed: {result.stderr.strip()}")
    return result.stdout


def git(repo: Path, *args: str) -> str:
    return run_command(["git", "-C", str(repo), *args], ["git", *args])


def read_json(path: Path) -> dict:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunError(f"cannot read {path}: {error}") from error
    if not isinstance(value, dict):
        raise RunError(f"{path} does not hold a JSON object")
    return value


def label(path: Path, repo: Path) -> str:
    """Name ``path`` without a machine-specific prefix: relative to the review root or the repo."""
    resolved = path.resolve()
    for root in (repo / REVIEWS_DIR, repo):
        if resolved.is_relative_to(root.resolve()):
            return str(resolved.relative_to(root.resolve()))
    return resolved.name


def latest_version(directory: Path) -> Path | None:
    """Return the highest ``v<N>.json`` in ``directory`` by numeric N, or None."""
    versions = []
    for path in directory.glob("v*.json"):
        match = VERSIONED.match(path.name)
        if match:
            versions.append((int(match.group(1)), path))
    return max(versions)[1] if versions else None


# --- snapshots -------------------------------------------------------------------------------


def is_bench_run(path: Path) -> bool:
    run = path / "run.json"
    if not run.is_file():
        return False
    try:
        return json.loads(run.read_text(encoding="utf-8")).get("schemaVersion") == BENCH_RUN_SCHEMA
    except (OSError, json.JSONDecodeError, AttributeError):
        return False


def is_refactoring_run(path: Path) -> bool:
    return (path / "merged.json").is_file() and (path / "plan.json").is_file()


def bench_snapshot(run_dir: Path) -> dict:
    """Snapshot one llm-review-bench run: per-system outcome, union verdicts, reviewer usage."""
    run = read_json(run_dir / "run.json")
    state = read_json(run_dir / "state.json")
    evaluation_path = latest_version(run_dir / "derived" / "evaluation")
    evaluation = read_json(evaluation_path) if evaluation_path else {}
    evaluated = evaluation.get("systems") or {}
    systems = []
    for agent, review in (state.get("steps", {}).get("reviews") or {}).items():
        attempts = review.get("attempts") or []
        result = evaluated.get(agent) or {}
        systems.append(
            {
                "agent": agent,
                "status": review.get("status"),
                "attempts": len(attempts),
                "durationMs": sum(a.get("durationMs") or 0 for a in attempts) if attempts else None,
                "findings": result.get("findings"),
                "usage": result.get("usage"),
            }
        )
    union = evaluation.get("union") or {}
    return {
        "runId": run.get("runId", run_dir.name),
        "status": state.get("status"),
        "systems": systems,
        "clusters": union.get("clusters"),
        "usage": evaluation.get("usage"),
    }


def report_dispositions(report: Path) -> dict[str, str]:
    """Map merged finding id -> disposition from the ``## Dispositions`` table of ``report.md``."""
    if not report.is_file():
        return {}
    dispositions: dict[str, str] = {}
    columns: list[str] | None = None
    in_section = False
    for line in report.read_text(encoding="utf-8").splitlines():
        if line.startswith("## "):
            in_section = line.strip() == "## Dispositions"
            columns = None
            continue
        if not in_section or not line.lstrip().startswith("|"):
            continue
        cells = [cell.strip() for cell in line.strip().strip("|").split("|")]
        if columns is None:
            columns = [cell.lower() for cell in cells]
            continue
        if set("".join(cells)) <= set("-: "):
            continue
        row = dict(zip(columns, cells))
        merged_id = row.get("merged id", "")
        words = re.findall(r"[a-z]+", row.get("disposition", "").lower())
        if merged_id:
            dispositions[merged_id] = words[0] if words and words[0] in DISPOSITIONS else "unknown"
    return dispositions


def refactoring_snapshot(run_dir: Path, repo: Path) -> dict:
    """Snapshot one refactoring-review run: findings by rule, tier, and disposition."""
    plan = read_json(run_dir / "plan.json")
    merged = read_json(run_dir / "merged.json")
    dispositions = report_dispositions(run_dir / "report.md")
    findings = [
        {
            "id": finding.get("id"),
            "rules": finding.get("rules") or [],
            "tier": finding.get("tier"),
            "scope": finding.get("scope"),
            "mechanizable": finding.get("mechanizable") is True,
            "disposition": dispositions.get(finding.get("id", ""), "unknown"),
        }
        for finding in merged.get("findings") or []
    ]
    totals = merged.get("totals") or {}
    return {
        "run": label(run_dir, repo),
        "head": plan.get("head"),
        "incompleteAgents": totals.get("incompleteAgents"),
        "findings": findings,
    }


def pit_files(path: Path) -> list[Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(p for p in path.rglob("*.xml") if is_mutations_xml(p))
    raise RunError(f"PIT report {path} does not exist")


def is_mutations_xml(path: Path) -> bool:
    with path.open("rb") as handle:
        return b"<mutations" in handle.read(512)


def pit_snapshot(path: Path, label: str) -> dict:
    """Count raw PIT statuses in one ``mutations.xml``; inconclusive per verification/pit.md."""
    try:
        root = ElementTree.parse(path).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise RunError(f"cannot parse PIT report {path}: {error}") from error
    statuses = Counter(m.get("status", "UNKNOWN") for m in root.iter("mutation"))
    total = sum(statuses.values())
    inconclusive = sum(n for status, n in statuses.items() if status not in PIT_CONCLUSIVE)
    return {
        "source": label,
        "statuses": dict(sorted(statuses.items())),
        "total": total,
        "inconclusive": inconclusive,
        "inconclusiveRate": round(inconclusive / total, 4) if total else None,
    }


# --- record ----------------------------------------------------------------------------------


def resolve_run(repo: Path, value: str) -> Path:
    path = Path(value)
    if not path.is_absolute():
        path = (Path.cwd() / path) if (Path.cwd() / path).exists() else repo / BENCH_RUNS / value
    if not path.is_dir():
        raise RunError(f"review run {value} is neither a directory nor a run id under {BENCH_RUNS}")
    return path


def parse_counts(values: list[str], flag: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        name, sep, number = value.rpartition("=")
        if not sep or not name or not number.isdigit():
            raise RunError(f"{flag} expects NAME=COUNT, got {value!r}")
        counts[name] = counts.get(name, 0) + int(number)
    return counts


def change_summary(repo: Path, base: str) -> dict:
    subjects = git(repo, "log", "--reverse", "--format=%s", f"{base}..HEAD").splitlines()
    if not subjects:
        raise RunError(f"no commits in {base}..HEAD; record after the change's commits")
    stat = git(repo, "diff", "--shortstat", f"{base}...HEAD")
    numbers = {key: int(n) for n, key in re.findall(r"(\d+) (file|insertion|deletion)", stat)}
    return {
        "commits": subjects,
        "files": numbers.get("file", 0),
        "insertions": numbers.get("insertion", 0),
        "deletions": numbers.get("deletion", 0),
    }


def build_record(args: argparse.Namespace, repo: Path) -> dict:
    if not TOPIC.match(args.topic):
        raise RunError(f"topic {args.topic!r} must be lowercase letters, digits, and dashes")
    date = args.date or datetime.date.today().isoformat()
    adversarial = []
    for value in args.adversarial:
        run_dir = resolve_run(repo, value)
        if not is_bench_run(run_dir):
            raise RunError(f"{run_dir} is not an llm-review-bench run (no {BENCH_RUN_SCHEMA} run.json)")
        adversarial.append(bench_snapshot(run_dir))
    refactoring = []
    for value in args.refactoring:
        run_dir = resolve_run(repo, value)
        if not is_refactoring_run(run_dir):
            raise RunError(f"{run_dir} is not a refactoring-review run (needs plan.json and merged.json)")
        refactoring.append(refactoring_snapshot(run_dir, repo))
    pit = []
    for value in args.pit:
        for path in pit_files(Path(value)):
            pit.append(pit_snapshot(path, label(path, repo)))
    if (args.cost is None) != (args.currency is None):
        raise RunError("--cost and --currency must be given together; costs in different currencies are never added")
    return {
        "schemaVersion": SCHEMA,
        "id": f"{date}-{args.topic}",
        "date": date,
        "topic": args.topic,
        "routes": sorted(set(args.route)),
        "implementer": {"agent": args.agent, "model": args.model},
        "change": change_summary(repo, args.base),
        "reviews": {"adversarial": adversarial, "refactoring": refactoring},
        "gates": {"failures": parse_counts(args.gate_failure, "--gate-failure")},
        "pit": pit,
        "interventions": {"asks": args.asks, "corrections": args.corrections},
        "usage": {"totalTokens": args.tokens, "cost": args.cost, "currency": args.currency},
        "notes": args.note,
    }


def command_record(args: argparse.Namespace, repo: Path) -> int:
    record = build_record(args, repo)
    path = repo / LEDGER_DIR / f"{record['id']}.json"
    if path.exists() and not args.force:
        raise RunError(f"{path} exists; pass --force to replace it")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(record, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"record": str(path.relative_to(repo)), "id": record["id"]}))
    return 0


# --- report ----------------------------------------------------------------------------------


def load_records(directory: Path) -> list[dict]:
    records = []
    for path in sorted(directory.glob("*.json")):
        record = read_json(path)
        if record.get("schemaVersion") != SCHEMA:
            raise RunError(f"{path} has schemaVersion {record.get('schemaVersion')!r}, expected {SCHEMA}")
        records.append(record)
    return records


def scan_runs(roots: list[Path], repo: Path) -> tuple[list[dict], list[dict]]:
    """Find bench and refactoring-review runs below ``roots`` (directories are not nested runs)."""
    adversarial: dict[str, dict] = {}
    refactoring: dict[str, dict] = {}
    for root in roots:
        if not root.is_dir():
            raise RunError(f"scan root {root} is not a directory")
        candidates = [root, *(p for p in root.rglob("*") if p.is_dir() and "snapshot" not in p.parts)]
        for path in candidates:
            if is_bench_run(path):
                snapshot = bench_snapshot(path)
                adversarial.setdefault(bench_identity(snapshot), snapshot)
            elif is_refactoring_run(path):
                snapshot = refactoring_snapshot(path, repo)
                refactoring.setdefault(refactoring_identity(snapshot), snapshot)
    return list(adversarial.values()), list(refactoring.values())


def rule_titles(repo: Path) -> dict[str, str]:
    titles = {"DEFECT": "Defect reported by a detector"}
    for path in sorted((repo / LENSES_DIR).glob("*.md")):
        titles.update(dict(RULE_HEADING.findall(path.read_text(encoding="utf-8"))))
    return titles


def change_row(record: dict) -> dict:
    adversarial = record["reviews"]["adversarial"]
    pit_total = sum(p["total"] for p in record["pit"])
    pit_inconclusive = sum(p["inconclusive"] for p in record["pit"])
    return {
        "id": record["id"],
        "routes": record["routes"],
        "commits": len(record["change"]["commits"]),
        "reviewRounds": sum(1 for run in adversarial if run["status"] == "completed"),
        "failedReviewRuns": sum(1 for run in adversarial if run["status"] != "completed"),
        "refactoringFindings": sum(len(run["findings"]) for run in record["reviews"]["refactoring"]),
        "gateFailures": sum(record["gates"]["failures"].values()),
        "asks": record["interventions"]["asks"],
        "corrections": record["interventions"]["corrections"],
        "pitInconclusiveRate": round(pit_inconclusive / pit_total, 4) if pit_total else None,
        "tokens": record["usage"]["totalTokens"],
        "cost": record["usage"]["cost"],
        "currency": record["usage"]["currency"],
    }


def rule_ranking(refactoring_runs: list[dict], titles: dict[str, str]) -> list[dict]:
    rows: dict[str, Counter] = {}
    for run in refactoring_runs:
        for finding in run["findings"]:
            for rule in dict.fromkeys(finding["rules"]):
                counts = rows.setdefault(rule, Counter())
                counts["findings"] += 1
                counts[finding["disposition"]] += 1
                counts["mechanizable"] += finding["mechanizable"]
    ranking = [
        {
            "rule": rule,
            "title": titles.get(rule),
            "findings": counts["findings"],
            **{name: counts[name] for name in (*DISPOSITIONS, "unknown")},
            "mechanizable": counts["mechanizable"],
        }
        for rule, counts in rows.items()
    ]
    return sorted(ranking, key=lambda row: (-row["findings"], -row["mechanizable"], row["rule"]))


def review_yield(adversarial_runs: list[dict]) -> dict:
    statuses = Counter()
    clusters = Counter()
    tokens = 0
    systems_with_usage = 0
    costs: Counter = Counter()
    for run in adversarial_runs:
        for system in run["systems"]:
            statuses[system["status"]] += 1
        for key, value in (run.get("clusters") or {}).items():
            clusters[key] += value or 0
        usage = run.get("usage") or {}
        if usage.get("totalTokens") is not None:
            tokens += usage["totalTokens"]
        systems_with_usage += usage.get("systemsWithUsage") or 0
        for entry in usage.get("costsByCurrency") or []:
            costs[entry.get("currency")] += entry.get("cost") or 0
    return {
        "runs": len(adversarial_runs),
        "systemStatuses": dict(sorted(statuses.items(), key=lambda item: str(item[0]))),
        "clusters": dict(clusters),
        "reviewerTokens": tokens if systems_with_usage else None,
        "systemsWithUsage": systems_with_usage,
        "reviewerCostsByCurrency": {str(currency): round(cost, 6) for currency, cost in costs.items()},
    }


Runner = Callable[[list[str]], str]


def run_gh(args: list[str]) -> str:
    return run_command(["gh", *args], ["gh", *args])


def ci_failures(limit: int, gh: Runner = run_gh) -> dict:
    """Count GitHub Actions runs per workflow and failed steps among the last ``limit`` runs."""
    runs = json.loads(gh(["run", "list", "--limit", str(limit), "--json", "databaseId,conclusion,workflowName"]))
    workflows: dict[str, Counter] = {}
    steps: Counter = Counter()
    for run in runs:
        counts = workflows.setdefault(run["workflowName"], Counter())
        counts["runs"] += 1
        if run.get("conclusion") != "failure":
            continue
        counts["failures"] += 1
        jobs = json.loads(gh(["run", "view", str(run["databaseId"]), "--json", "jobs"])).get("jobs") or []
        failed = [
            f"{run['workflowName']} / {job['name']} / {step['name']}"
            for job in jobs
            if job.get("conclusion") == "failure"
            for step in job.get("steps") or []
            if step.get("conclusion") == "failure"
        ]
        steps.update(failed or [f"{run['workflowName']} / (no failed step reported)"])
    return {
        "runsInspected": len(runs),
        "workflows": {name: {"runs": c["runs"], "failures": c["failures"]} for name, c in sorted(workflows.items())},
        "failedSteps": [{"step": step, "count": n} for step, n in steps.most_common()],
    }


def bench_identity(run: dict) -> str:
    return run["runId"]


def refactoring_identity(run: dict) -> str:
    return run["head"] or run["run"]


def merge_runs(records: list[dict], kind: str, scanned: list[dict], identity: Callable[[dict], str]) -> tuple[list[dict], list[dict]]:
    """Return (recorded runs followed by unrecorded scanned runs, the unrecorded scanned runs)."""
    recorded = [run for record in records for run in record["reviews"][kind]]
    known = {identity(run) for run in recorded}
    extra = [run for run in scanned if identity(run) not in known]
    return recorded + extra, extra


def build_report(records: list[dict], scanned: tuple[list[dict], list[dict]], titles: dict[str, str]) -> dict:
    adversarial, extra_bench = merge_runs(records, "adversarial", scanned[0], bench_identity)
    refactoring, extra_refactoring = merge_runs(records, "refactoring", scanned[1], refactoring_identity)
    gates: Counter = Counter()
    for record in records:
        gates.update(record["gates"]["failures"])
    return {
        "records": len(records),
        "unrecordedRuns": {"adversarial": len(extra_bench), "refactoring": len(extra_refactoring)},
        "changes": [change_row(record) for record in records],
        "rules": rule_ranking(refactoring, titles),
        "localGateFailures": dict(gates.most_common()),
        "reviewYield": review_yield(adversarial),
    }


def cell(value: object) -> str:
    if value is None:
        return "-"
    if isinstance(value, list):
        return ", ".join(map(str, value)) or "-"
    return str(value)


def table(headers: list[str], rows: list[list[object]]) -> list[str]:
    lines = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    lines += ["| " + " | ".join(cell(value) for value in row) + " |" for row in rows]
    return lines


def render_markdown(report: dict) -> str:
    lines = [
        "# Process metrics",
        "",
        f"Records: {report['records']}. Unrecorded runs found by --scan: "
        f"{report['unrecordedRuns']['adversarial']} adversarial, {report['unrecordedRuns']['refactoring']} refactoring.",
        "",
        "## Changes",
        "",
    ]
    lines += table(
        ["Change", "Routes", "Commits", "Review rounds", "Failed review runs", "Refactoring findings",
         "Gate failures", "Asks", "Corrections", "PIT inconclusive", "Tokens", "Cost"],
        [[c["id"], c["routes"], c["commits"], c["reviewRounds"], c["failedReviewRuns"], c["refactoringFindings"],
          c["gateFailures"], c["asks"], c["corrections"], c["pitInconclusiveRate"], c["tokens"],
          None if c["cost"] is None else f"{c['cost']} {c['currency']}"] for c in report["changes"]],
    )
    lines += ["", "## Rules by findings", ""]
    lines += table(
        ["Rule", "Title", "Findings", *DISPOSITIONS, "unknown", "Mechanizable"],
        [[r["rule"], r["title"], r["findings"], *(r[d] for d in DISPOSITIONS), r["unknown"], r["mechanizable"]]
         for r in report["rules"]],
    )
    lines += ["", "## Local gate failures (self-reported)", ""]
    lines += table(["Gate", "Failures"], [[gate, n] for gate, n in report["localGateFailures"].items()])
    ci = report.get("ci")
    if ci:
        lines += ["", f"## CI ({ci['runsInspected']} most recent runs)", ""]
        lines += table(["Workflow", "Runs", "Failures"], [[n, w["runs"], w["failures"]] for n, w in ci["workflows"].items()])
        lines += [""]
        lines += table(["Failed step", "Count"], [[s["step"], s["count"]] for s in ci["failedSteps"]])
    review = report["reviewYield"]
    lines += [
        "",
        "## Adversarial review yield",
        "",
        f"Runs: {review['runs']}. Reviewer statuses: {cell([f'{k}={v}' for k, v in review['systemStatuses'].items()])}.",
        f"Union clusters: {cell([f'{k}={v}' for k, v in review['clusters'].items()])}.",
        f"Reviewer tokens: {cell(review['reviewerTokens'])} ({review['systemsWithUsage']} systems reported usage). "
        f"Reviewer cost: {cell([f'{v} {k}' for k, v in review['reviewerCostsByCurrency'].items()])}.",
        "",
    ]
    return "\n".join(lines)


def command_report(args: argparse.Namespace, repo: Path) -> int:
    ledger = Path(args.ledger) if args.ledger else repo / LEDGER_DIR
    records = load_records(ledger) if ledger.is_dir() else []
    scanned = scan_runs([Path(root) for root in args.scan], repo)
    report = build_report(records, scanned, rule_titles(repo))
    if args.ci:
        report["ci"] = ci_failures(args.ci)
    print(json.dumps(report, indent=2, ensure_ascii=False) if args.json else render_markdown(report))
    return 0


# --- entry -----------------------------------------------------------------------------------


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    root.add_argument("--repo", default=".", help="repository root (default: current directory)")
    sub = root.add_subparsers(dest="command", required=True)

    record = sub.add_parser("record", help="write one delivery record")
    record.add_argument("--topic", required=True, help="lowercase dashed topic, usually the branch name")
    record.add_argument("--route", action="append", choices=ROUTES, required=True,
                        help="AGENTS.md task route; repeat when routes combine")
    record.add_argument("--base", default="@{upstream}", help="development-line ref the change starts from")
    record.add_argument("--date", help="record date, YYYY-MM-DD (default: today)")
    record.add_argument("--agent", help="implementer harness, e.g. claude, codex, kiro")
    record.add_argument("--model", help="implementer model as the harness names it")
    record.add_argument("--adversarial", action="append", default=[], metavar="RUN",
                        help="llm-review-bench run directory or run id, one per review round, in order")
    record.add_argument("--refactoring", action="append", default=[], metavar="DIR",
                        help="refactoring-review run directory")
    record.add_argument("--pit", action="append", default=[], metavar="PATH",
                        help="mutations.xml, or a directory searched for PIT XML reports")
    record.add_argument("--gate-failure", action="append", default=[], metavar="GATE=N",
                        help="failed runs of a required local check before it passed")
    record.add_argument("--asks", type=int, default=0, help="times the agent stopped for a maintainer decision")
    record.add_argument("--corrections", type=int, default=0, help="times the user corrected the agent")
    record.add_argument("--tokens", type=int, help="implementer total tokens, only when the harness reports them")
    record.add_argument("--cost", type=float, help="implementer cost, only when the harness reports it")
    record.add_argument("--currency", help="currency of --cost")
    record.add_argument("--note", default="", help="one line of context for later analysis")
    record.add_argument("--force", action="store_true", help="replace an existing record with the same id")

    report = sub.add_parser("report", help="aggregate records and scanned runs")
    report.add_argument("--ledger", help=f"record directory (default: {LEDGER_DIR})")
    report.add_argument("--scan", action="append", default=[], metavar="DIR",
                        help="also aggregate run directories below DIR that no record names")
    report.add_argument("--ci", type=int, metavar="N", help="add failed steps from the last N GitHub Actions runs")
    report.add_argument("--json", action="store_true", help="print JSON instead of Markdown")
    return root


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    repo = Path(args.repo).resolve()
    try:
        return command_record(args, repo) if args.command == "record" else command_report(args, repo)
    except RunError as error:
        print(f"process-metrics: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
