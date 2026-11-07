#!/usr/bin/env python3
"""Deterministic parts of refactoring-review: the gate, the detector plan, the merge, and verify.

The rules, thresholds, and templates live in ``refactoring-review/`` and are read from the review
base commit, so a change cannot relax the rules that review it. See ``refactoring-review/AGENTS.md``.

Subcommands:

``plan``
    Decide whether the change from the merge-base of ``--base`` and ``--head`` needs
    refactoring-review. When it does, write the run directory: ``plan.json``, the full and
    per-detector diffs, the rules as read, and one filled detector brief per detector.
``detect``
    Run each planned detector's brief through a read-only reviewer CLI (kimi, codex, claude), at most
    ``--concurrency`` at a time, and save the parsed JSON as ``agents/<id>/findings.json``.
``merge``
    Validate each detector's ``agents/<id>/findings.json``, enforce per-detector budgets, merge
    duplicates, and write ``merged.json``.
``verify``
    After the refactor commits, measure their code-line growth against the behavior change, list
    test lines that touch assertions or timing, and list commits that are not ``refactor:`` commits.

Every subcommand prints one JSON object on stdout. Exit status: 0 success; 1 a check failed (detect:
a detector did not complete; merge: a detector missing or invalid; verify: a stop rule, a test-oracle
edit, or a non-refactor commit); 2 the command could not run.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime
import fnmatch
import hashlib
import json
import os
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import complexity_metrics

RULES_DIR = "refactoring-review"
CONFIG_PATH = f"{RULES_DIR}/config.json"
BRIEF_PATH = f"{RULES_DIR}/detector-brief.md"
PRINCIPLES_PATH = f"{RULES_DIR}/principles.md"
PLACEHOLDER = re.compile(r"\{\{(\w+)\}\}")
RULE_HEADING = re.compile(r"^### ([A-Z][0-9]{2})\b", re.MULTILINE)
TIERS = ("T0", "T1", "T2")
SCOPES = ("in-diff", "out-of-diff")
REQUIRED_FIELDS = ("file", "symbol", "rule", "tier", "refactoring", "rationale")
DEFECT_RULE = "DEFECT"


class RunError(Exception):
    """The command cannot run; reported with exit status 2."""


@dataclass
class ChangedFile:
    path: str
    old_path: str
    ext: str
    cls: str | None = None
    code_lines: int = 0
    comment_lines: int = 0
    changed_code: list[str] = field(default_factory=list)

    def summary(self) -> dict:
        return {
            "path": self.path,
            "class": self.cls,
            "codeLines": self.code_lines,
            "commentLines": self.comment_lines,
        }


def git(repo: Path, *args: str, check: bool = True) -> str:
    result = subprocess.run(["git", *args], cwd=repo, capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RunError(f"git {' '.join(args)} failed: {result.stderr.strip()}")
    return result.stdout


def resolve(repo: Path, ref: str) -> str:
    sha = git(repo, "rev-parse", "--verify", "--quiet", f"{ref}^{{commit}}", check=False).strip()
    if not sha:
        raise RunError(f"ref {ref} does not resolve to a commit")
    return sha


def matches(path: str, globs: list[str]) -> bool:
    return any(fnmatch.fnmatchcase(path, glob) for glob in globs)


# ---------------------------------------------------------------------------- rules


def read_rules(repo: Path, base: str, head: str) -> tuple[str, dict[str, str]]:
    """Return where the rules came from and their texts keyed by repository path."""
    for source, rev in (("base", base), ("head", head)):
        listing = git(repo, "ls-tree", "-r", "--name-only", rev, "--", f"{RULES_DIR}/", check=False)
        paths = [line for line in listing.splitlines() if line]
        if CONFIG_PATH in paths:
            return source, {path: git(repo, "show", f"{rev}:{path}") for path in paths}
    raise RunError(f"{CONFIG_PATH} exists in neither the base nor the head commit")


def load_config(rules: dict[str, str]) -> dict:
    try:
        return json.loads(rules[CONFIG_PATH])
    except (KeyError, json.JSONDecodeError) as error:
        raise RunError(f"cannot read {CONFIG_PATH}: {error}") from error


def fill_missing_keys(config: dict, newer: dict) -> list[str]:
    """Add keys the base config predates from the head config; never override an existing value.

    A change that teaches the script a new setting would otherwise fail its own review, while
    keeping every base value means the change still cannot relax a rule that reviews it.
    """
    added = []
    def fill(prefix: str, current: dict, incoming: dict) -> None:
        for key, value in incoming.items():
            name = f"{prefix}.{key}" if prefix else key
            if key not in current:
                current[key] = value
                added.append(name)
            elif isinstance(value, dict) and isinstance(current[key], dict):
                fill(name, current[key], value)

    for key, value in newer.items():
        if key not in config:
            config[key] = value
            added.append(key)
        elif isinstance(value, dict) and isinstance(config[key], dict):
            fill(key, config[key], value)
    return added


def rule_ids(text: str) -> list[str]:
    return RULE_HEADING.findall(text)


# ---------------------------------------------------------------------------- diff parsing


def classify(path: str, config: dict) -> str | None:
    ext = Path(path).suffix
    if ext not in config["codeExtensions"] or matches(path, config["excludeGlobs"]):
        return None
    for entry in config["pathClasses"]:
        if matches(path, entry["globs"]):
            return entry["class"]
    return None


def is_comment(text: str, ext: str, config: dict) -> bool:
    return any(text.startswith(prefix) for prefix in config["commentPrefixes"].get(ext, []))


def parse_diff(patch: str, config: dict) -> list[ChangedFile]:
    """Count changed code and comment lines per file of a ``-w --unified=0`` patch."""
    files: list[ChangedFile] = []
    current: ChangedFile | None = None
    in_hunk = False
    for line in patch.splitlines():
        if line.startswith("diff --git "):
            in_hunk = False
            current = None
            parts = line.split(" b/", 1)
            if len(parts) == 2:
                new_path = parts[1]
                old_path = parts[0][len("diff --git a/"):]
                current = ChangedFile(new_path, old_path, Path(new_path).suffix)
                files.append(current)
            continue
        if current is None:
            continue
        if not in_hunk:
            if line.startswith("+++ ") and line != "+++ /dev/null":
                current.path = line[len("+++ b/"):]
                current.ext = Path(current.path).suffix
            elif line == "+++ /dev/null":
                current.path = current.old_path
                current.ext = Path(current.path).suffix
            elif line.startswith("@@"):
                in_hunk = True
            continue
        if line.startswith("@@"):
            continue
        if line[:1] not in ("+", "-"):
            continue
        text = line[1:].strip()
        if not text:
            continue
        if is_comment(text, current.ext, config):
            current.comment_lines += 1
        else:
            current.code_lines += 1
            current.changed_code.append(text)
    for changed in files:
        changed.cls = classify(changed.path, config)
    return files


def changed_files(repo: Path, base: str, head: str, config: dict) -> list[ChangedFile]:
    patch = git(repo, "diff", "-w", "--no-color", "--no-ext-diff", "--unified=0", "-M", base, head)
    return parse_diff(patch, config)


# ---------------------------------------------------------------------------- planning


def lens_files(lens: dict, files: list[ChangedFile]) -> list[ChangedFile]:
    relevant = [
        f
        for f in files
        if f.cls in lens["classes"] and (not lens.get("extensions") or f.ext in lens["extensions"])
    ]
    pattern = lens.get("changedLinePattern")
    globs = lens.get("pathGlobs", [])
    if pattern or globs:
        compiled = re.compile(pattern) if pattern else None
        relevant = [
            f
            for f in relevant
            if (compiled and any(compiled.search(text) for text in f.changed_code)) or matches(f.path, globs)
        ]
    if len(relevant) < lens.get("minFiles", 1):
        return []
    return relevant


def shard(files: list[ChangedFile], size: int, min_lines: int = 0) -> list[list[ChangedFile]]:
    """Pack files into shards of about ``size`` lines, keeping each directory together when it fits.

    A single file larger than ``size`` forms its own shard; a shard below ``min_lines`` merges into
    the smallest other shard.
    """
    groups: dict[str, list[ChangedFile]] = {}
    for changed in sorted(files, key=lambda f: f.path):
        groups.setdefault(str(Path(changed.path).parent), []).append(changed)
    shards: list[list[ChangedFile]] = []
    current: list[ChangedFile] = []
    lines = 0

    def flush() -> None:
        nonlocal current, lines
        if current:
            shards.append(current)
        current, lines = [], 0

    for directory in sorted(groups):
        group = groups[directory]
        group_lines = sum(f.code_lines for f in group)
        if group_lines > size:
            flush()
            for changed in group:
                if current and lines + changed.code_lines > size:
                    flush()
                current.append(changed)
                lines += changed.code_lines
            flush()
        else:
            if current and lines + group_lines > size:
                flush()
            current.extend(group)
            lines += group_lines
    flush()
    # A leftover shard too small to deserve its own detector joins the smallest other shard.
    while len(shards) > 1:
        sizes = [sum(f.code_lines for f in piece) for piece in shards]
        small = [index for index, lines in enumerate(sizes) if lines < min_lines]
        if not small:
            break
        index = small[0]
        others = [other for other in range(len(shards)) if other != index]
        target = min(others, key=lambda other: (sizes[other], other))
        shards[target] = sorted(shards[target] + shards[index], key=lambda f: f.path)
        del shards[index]
    return shards


def budgets(agents: list[dict], limits: dict) -> list[int]:
    count = len(agents)
    if count == 1:
        return [min(limits["singleAgentBudget"], limits["findingBudget"])]
    base, remainder = divmod(limits["findingBudget"], count)
    result = [max(limits["minBudgetPerAgent"], base + (1 if index < remainder else 0)) for index in range(count)]
    for index, agent in enumerate(agents):
        while not agent["shardable"] and result[index] < limits["wholeDiffLensMinBudget"]:
            donors = [
                other
                for other in range(count)
                if other != index and agents[other]["shardable"] and result[other] > limits["minBudgetPerAgent"]
            ]
            if not donors:
                result[index] = limits["wholeDiffLensMinBudget"]
                break
            donor = max(donors, key=lambda other: (result[other], -other))
            result[donor] -= 1
            result[index] += 1
    return result


def route_matches(route: dict, changed: ChangedFile) -> bool:
    if "classes" in route and changed.cls not in route["classes"]:
        return False
    if "pathGlobs" in route and not matches(changed.path, route["pathGlobs"]):
        return False
    if "excludeGlobs" in route and matches(changed.path, route["excludeGlobs"]):
        return False
    if "extensions" in route and changed.ext not in route["extensions"]:
        return False
    if "changedLinePattern" in route:
        pattern = re.compile(route["changedLinePattern"])
        if not any(pattern.search(text) for text in changed.changed_code):
            return False
    if route.get("commentLines") and changed.comment_lines == 0:
        return False
    return True


def routes_for(files: list[ChangedFile], config: dict) -> list[str]:
    documents: list[str] = []
    for route in config["routes"]:
        if any(route_matches(route, changed) for changed in files):
            for document in route["read"]:
                if document not in documents:
                    documents.append(document)
    return documents


def build_plan(files: list[ChangedFile], config: dict) -> dict:
    """Pure planning step: gate, lens triggers, mode, detectors, budgets, and uncovered work."""
    limits = config["limits"]
    code = [f for f in files if f.cls is not None and f.code_lines > 0]
    skipped = [
        {"path": f.path, "reason": "not a code path" if f.cls is None else "no changed code lines"}
        for f in files
        if f not in code
    ]
    total = sum(f.code_lines for f in code)
    plan: dict = {
        "required": bool(code),
        "reason": f"{len(code)} code file(s), {total} changed code line(s)"
        if code
        else "no changed code lines in code paths",
        "totals": {"codeFiles": len(code), "codeLines": total},
        "files": [f.summary() for f in code],
        "skippedFiles": skipped,
        "lenses": [],
        "mode": None,
        "agents": [],
        "uncovered": [],
        "limits": limits,
    }
    if not code:
        return plan

    triggered = [(lens, lens_files(lens, code)) for lens in config["lenses"]]
    triggered = [(lens, relevant) for lens, relevant in triggered if relevant]
    plan["lenses"] = [lens["id"] for lens, _ in triggered]

    candidates: list[dict] = []
    if total <= limits["singleAgentMaxLines"]:
        plan["mode"] = "single"
        covered = {f.path for _, relevant in triggered for f in relevant}
        candidates.append(
            {
                "id": "all",
                "lenses": plan["lenses"],
                "shard": None,
                "shardable": True,
                "files": [f for f in code if f.path in covered],
            }
        )
    elif total <= limits["perLensMaxLines"]:
        plan["mode"] = "per-lens"
        for lens, relevant in triggered:
            candidates.append(
                {"id": lens["id"], "lenses": [lens["id"]], "shard": None, "shardable": lens["shardable"], "files": relevant}
            )
    else:
        plan["mode"] = "sharded"
        sharded: list[tuple[dict, list[list[ChangedFile]]]] = []
        for lens, relevant in triggered:
            if lens["shardable"]:
                # Largest shards first, so the agent cap drops the smallest work into "uncovered".
                pieces = shard(relevant, limits["shardLines"], limits["minShardLines"])
                pieces.sort(key=lambda piece: (-sum(f.code_lines for f in piece), piece[0].path))
                sharded.append((lens, pieces))
            else:
                candidates.append(
                    {"id": lens["id"], "lenses": [lens["id"]], "shard": None, "shardable": False, "files": relevant}
                )
        rounds = max((len(pieces) for _, pieces in sharded), default=0)
        for index in range(rounds):
            for lens, pieces in sharded:
                if index < len(pieces):
                    candidates.append(
                        {
                            "id": f"{lens['id']}-{index + 1}",
                            "lenses": [lens["id"]],
                            "shard": index + 1,
                            "shardable": True,
                            "files": pieces[index],
                        }
                    )

    accepted = candidates[: limits["maxAgents"]]
    for extra in candidates[limits["maxAgents"]:]:
        plan["uncovered"].append(
            {
                "lens": extra["lenses"][0],
                "shard": extra["shard"],
                "files": [f.path for f in extra["files"]],
                "codeLines": sum(f.code_lines for f in extra["files"]),
            }
        )
    for agent, budget in zip(accepted, budgets(accepted, limits)):
        plan["agents"].append(
            {
                "id": agent["id"],
                "lenses": agent["lenses"],
                "shard": agent["shard"],
                "wholeDiff": not agent["shardable"],
                "files": [f.path for f in agent["files"]],
                "codeLines": sum(f.code_lines for f in agent["files"]),
                "budget": budget,
                "routes": routes_for(agent["files"], config),
                "reviewer": None,
            }
        )
    return plan


def complexity_inputs(repo: Path, base: str, head: str, config: dict) -> tuple[dict[str, str], dict[str, str], str]:
    """Return the core Java sources and the diff used by the gray-stage collector."""
    paths = git(repo, "diff", "--name-only", "--diff-filter=ACMR", base, head).splitlines()
    paths = [
        path
        for path in paths
        if path.startswith("core/src/main/java/io/github/monadrome/parallelinscope/")
        and path.endswith(".java")
        and "/queues/" not in path
    ]
    base_files: dict[str, str] = {}
    head_files: dict[str, str] = {}
    for path in paths:
        base_text = git(repo, "show", f"{base}:{path}", check=False)
        head_text = git(repo, "show", f"{head}:{path}", check=False)
        if base_text:
            base_files[path] = base_text
        if head_text:
            head_files[path] = head_text
    diff = git(repo, "diff", "--no-color", "--no-ext-diff", "--unified=0", base, head, "--", *paths) if paths else ""
    return base_files, head_files, diff


def build_complexity_evidence(repo: Path, base: str, head: str, config: dict) -> dict:
    """Collect gray-stage evidence without changing the review gate."""
    complexity = config.get("complexity")
    if not complexity or not complexity.get("enabled", False):
        return {
            "version": "complexity-metrics/v1",
            "stage": "disabled",
            "purpose": "attention-routing evidence; not a quality score or CI gate",
            "summary": {"methods": 0, "touchedCandidates": 0, "injectedCandidates": 0},
            "candidates": [],
        }
    collector_config = dict(config)
    collector_config["_base"] = base
    collector_config["_head"] = head
    try:
        base_files, head_files, diff = complexity_inputs(repo, base, head, config)
        evidence = complexity_metrics.collect(repo, base_files, head_files, diff, collector_config)
        evidence["status"] = "completed"
        return evidence
    except (OSError, ValueError, KeyError, RunError) as error:
        return {
            "version": "complexity-metrics/v1",
            "stage": "gray-evaluation",
            "status": "blocked",
            "purpose": "attention-routing evidence; not a quality score or CI gate",
            "error": f"{type(error).__name__}: {error}",
            "base": base,
            "head": head,
            "summary": {"methods": 0, "touchedCandidates": 0, "injectedCandidates": 0},
            "candidates": [],
        }


def complexity_brief(evidence: dict, files: list[str]) -> str:
    if evidence.get("stage") != "gray-evaluation":
        return "Complexity evidence is disabled for this run."
    if evidence.get("status") == "blocked":
        return f"Complexity gray evaluation is blocked: {evidence.get('error', 'unknown error')}. Do not treat this as a clean review."
    candidates = [candidate for candidate in evidence.get("candidates", []) if candidate["path"] in files]
    if not candidates:
        return (
            "Gray evaluation is enabled, but no complexity candidate was assigned to this detector. "
            "This evidence is advisory only and never a quality score or CI gate."
        )
    lines = [
        "Gray evaluation evidence (advisory only; not a quality score or CI gate):",
        "Before creating a finding, explain the semantic responsibility that makes a candidate worth restructuring. "
        "High complexity may be intrinsic to an algorithm or concurrency protocol and may be rejected.",
    ]
    for index, candidate in enumerate(candidates, start=1):
        lines.append(
            f"- C{index}: `{candidate['path']}` `{candidate['symbol']}`; "
            f"CC={candidate['cyclomatic']}, codeLines={candidate['codeLines']}, "
            f"maxNesting={candidate['maxNesting']}; triggers={', '.join(candidate['triggers'])}"
        )
    return "\n".join(lines)


def assign_reviewers(plan: dict, reviewers: list[str], base: str, head: str) -> None:
    if not reviewers:
        return
    offset = int(hashlib.sha256(f"{base}{head}".encode()).hexdigest(), 16) % len(reviewers)
    for index, agent in enumerate(plan["agents"]):
        agent["reviewer"] = reviewers[(offset + index) % len(reviewers)]


def render_brief(template: str, values: dict[str, str]) -> str:
    _, separator, prompt = template.partition("\n---\n")
    if not separator:
        raise RunError(f"{BRIEF_PATH} has no '---' line separating its header from the prompt")
    filled = PLACEHOLDER.sub(lambda match: values.get(match.group(1), match.group(0)), prompt)
    leftover = sorted(set(PLACEHOLDER.findall(filled)))
    if leftover:
        raise RunError(f"{BRIEF_PATH} has unfilled placeholders: {', '.join(leftover)}")
    return filled.lstrip("\n")


def display_path(path: Path, repo: Path) -> str:
    try:
        return str(path.resolve().relative_to(repo.resolve()))
    except ValueError:
        return str(path.resolve())


def dirty_code_paths(repo: Path, config: dict) -> list[str]:
    status = git(repo, "status", "--porcelain", "--untracked-files=no")
    paths = [line[3:].split(" -> ")[-1] for line in status.splitlines() if len(line) > 3]
    return [path for path in paths if classify(path, config) is not None]


def default_run_dir(repo: Path, config: dict, head: str) -> Path:
    branch = git(repo, "rev-parse", "--abbrev-ref", "HEAD", check=False).strip()
    label = branch if branch and branch != "HEAD" else head[:12]
    label = re.sub(r"[^A-Za-z0-9._-]+", "-", label).strip("-")
    return repo / config["reportDir"] / f"refactoring-{label}-{datetime.date.today().isoformat()}"


def command_plan(args: argparse.Namespace, repo: Path) -> int:
    head = resolve(repo, args.head)
    base_ref = args.base
    if base_ref is None:
        base_ref = load_config(read_rules(repo, head, head)[1])["defaultBase"]
    merge_base = git(repo, "merge-base", resolve(repo, base_ref), head).strip()
    if not merge_base:
        raise RunError(f"{base_ref} and {args.head} have no merge base")
    source, rules = read_rules(repo, merge_base, head)
    config = load_config(rules)
    head_config = load_config(read_rules(repo, head, head)[1])
    keys_from_head = fill_missing_keys(config, head_config) if source == "base" else []
    rules[CONFIG_PATH] = json.dumps(config, indent=2) + "\n"

    if args.head == "HEAD" and not args.allow_dirty:
        dirty = dirty_code_paths(repo, config)
        if dirty:
            raise RunError("commit the behavior change first; uncommitted code paths: " + ", ".join(dirty))

    plan = build_plan(changed_files(repo, merge_base, head, config), config)
    evidence = build_complexity_evidence(repo, merge_base, head, config)
    plan = {
        "version": 1,
        "baseRef": base_ref,
        "base": merge_base,
        "head": head,
        "rulesSource": source,
        "configKeysFromHead": keys_from_head,
        "evaluation": {
            "stage": "gray-evaluation",
            "purpose": "attention-routing evidence; not a quality score or CI gate",
            "complexity": evidence,
        },
        **plan,
    }
    assign_reviewers(plan, [r for r in (args.reviewers or "").split(",") if r], merge_base, head)

    run_dir = Path(args.output_dir) if args.output_dir else default_run_dir(repo, config, head)
    if (run_dir / "plan.json").exists() and not args.force:
        raise RunError(f"{run_dir}/plan.json already exists; pass --force or another --output-dir")
    run_dir.mkdir(parents=True, exist_ok=True)
    (run_dir / "metrics.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")

    if plan["required"]:
        # A pathspec of new names shows a renamed file as added in full; detectors need its content anyway.
        paths = [f["path"] for f in plan["files"]]
        (run_dir / "diff.patch").write_text(
            git(repo, "diff", "--no-color", "--no-ext-diff", "-M", merge_base, head, "--", *paths), encoding="utf-8"
        )
        (run_dir / "commits.txt").write_text(
            git(repo, "log", "--oneline", "--no-decorate", f"{merge_base}..{head}"), encoding="utf-8"
        )
        for path, text in rules.items():
            target = run_dir / "rules" / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        lens_by_id = {lens["id"]: lens for lens in config["lenses"]}
        line_counts = {f["path"]: f["codeLines"] for f in plan["files"]}
        lens_texts = {}
        for lens_id, lens in lens_by_id.items():
            lens_path = f"{RULES_DIR}/{lens['file']}"
            if lens_path not in rules:
                raise RunError(f"lens {lens_id} names {lens_path}, which the {source} commit does not contain")
            lens_texts[lens_id] = rules[lens_path].strip()
        for agent in plan["agents"]:
            agent_dir = run_dir / "agents" / agent["id"]
            agent_dir.mkdir(parents=True, exist_ok=True)
            diff_path = agent_dir / "diff.patch"
            diff_path.write_text(
                git(repo, "diff", "--no-color", "--no-ext-diff", "-M", merge_base, head, "--", *agent["files"]),
                encoding="utf-8",
            )
            values = {
                "agent_id": agent["id"],
                "lens_ids": ", ".join(f"`{lens}` ({lens_by_id[lens]['name']})" for lens in agent["lenses"]),
                "base": merge_base,
                "head": head,
                "diff_path": display_path(diff_path, repo),
                "files": "\n".join(f"- `{path}` ({line_counts[path]} changed code lines)" for path in agent["files"]),
                "routes": "\n".join(f"   - `{document}`" for document in agent["routes"]) or "   - none",
                "budget": str(agent["budget"]),
                "principles": rules[PRINCIPLES_PATH].strip(),
                "lens_rules": "\n\n".join(lens_texts[lens] for lens in agent["lenses"]),
                "complexity_evidence": complexity_brief(evidence, agent["files"])
                if "S" in agent["lenses"]
                else "Complexity evidence is assigned only to the structure lens.",
            }
            brief_path = agent_dir / "brief.md"
            brief_path.write_text(render_brief(rules[BRIEF_PATH], values), encoding="utf-8")
            agent["diff"] = display_path(diff_path, repo)
            agent["brief"] = display_path(brief_path, repo)
            agent["findings"] = display_path(agent_dir / "findings.json", repo)

    (run_dir / "plan.json").write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "required": plan["required"],
                "reason": plan["reason"],
                "runDir": display_path(run_dir, repo),
                "mode": plan["mode"],
                "agents": [
                    {"id": a["id"], "lenses": a["lenses"], "budget": a["budget"], "reviewer": a["reviewer"]}
                    for a in plan["agents"]
                ],
                "uncovered": plan["uncovered"],
            },
            indent=2,
        )
    )
    return 0


# ---------------------------------------------------------------------------- detect


def kimi_argv(prompt: str, repo: Path, agent_dir: Path, last_message: Path) -> list[str]:
    return ["kimi", "--output-format", "text", "--add-dir", str(agent_dir), "-p", prompt]


def codex_argv(prompt: str, repo: Path, agent_dir: Path, last_message: Path) -> list[str]:
    return [
        "codex", "exec", "-C", str(repo), "--sandbox", "read-only",
        "--add-dir", str(agent_dir), "--output-last-message", str(last_message), prompt,
    ]


def claude_argv(prompt: str, repo: Path, agent_dir: Path, last_message: Path) -> list[str]:
    return ["claude", "-p", prompt]


# Argv builders for detectors run by `detect`; the read-only flags mirror the review bench adapters
# (tools/llm-review-bench/docs/adapters.md). Tests register extra entries.
REVIEWERS = {"kimi": kimi_argv, "codex": codex_argv, "claude": claude_argv}


def extract_findings(text: str) -> dict | None:
    """Return the last JSON object in ``text`` that carries a ``findings`` array."""
    decoder = json.JSONDecoder()
    found = None
    for index, char in enumerate(text):
        if char != "{":
            continue
        try:
            value, _ = decoder.raw_decode(text[index:])
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and isinstance(value.get("findings"), list):
            found = value
    return found


def _terminate_process_group(process: subprocess.Popen[str], grace: float) -> str:
    def group_exists() -> bool:
        try:
            os.killpg(process.pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            # macOS may report a transient EPERM while a process in the group is
            # exiting. Treat the group as alive so timeout cleanup continues to
            # SIGKILL instead of rewriting the result as not-runnable.
            return True

    if process.poll() is not None and not group_exists():
        return "exited"
    try:
        os.killpg(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        return "exited" if process.poll() is not None else "terminated"
    except PermissionError:
        pass
    deadline = time.monotonic() + grace
    while time.monotonic() < deadline and group_exists():
        if process.poll() is not None and not group_exists():
            return "terminated"
        time.sleep(0.05)
    if group_exists():
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except (ProcessLookupError, PermissionError):
            pass
    return "killed"


def run_detector(agent: dict, reviewer: str, repo: Path, run_dir: Path, timeout: int, kill_grace: int) -> dict:
    agent_dir = run_dir / "agents" / agent["id"]
    attempt = len(list(agent_dir.glob("attempt-*.stdout.log"))) + 1
    stdout_path = agent_dir / f"attempt-{attempt}.stdout.log"
    stderr_path = agent_dir / f"attempt-{attempt}.stderr.log"
    last_message = agent_dir / f"attempt-{attempt}.last-message.txt"
    progress_path = agent_dir / f"attempt-{attempt}.progress.jsonl"
    argv = REVIEWERS[reviewer]((agent_dir / "brief.md").read_text(encoding="utf-8"), repo, agent_dir, last_message)
    started = datetime.datetime.now()
    status = "completed"
    with stdout_path.open("w", encoding="utf-8") as stdout, stderr_path.open("w", encoding="utf-8") as stderr, progress_path.open("w", encoding="utf-8") as progress:
        process: subprocess.Popen[str] | None = None
        deadline = time.monotonic() + timeout
        try:
            process = subprocess.Popen(
                argv,
                cwd=repo,
                stdout=stdout,
                stderr=stderr,
                start_new_session=True,
                text=True,
            )
            while process.poll() is None:
                now = time.monotonic()
                progress.write(json.dumps({
                    "at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                    "elapsedSeconds": round(now - (deadline - timeout), 3),
                    "pid": process.pid,
                }) + "\n")
                progress.flush()
                remaining = deadline - now
                if remaining <= 0:
                    _terminate_process_group(process, kill_grace)
                    status = "timeout"
                    break
                try:
                    process.wait(timeout=min(1.0, remaining))
                except subprocess.TimeoutExpired:
                    continue
            if process.poll() is not None and status == "completed" and process.returncode != 0:
                status = f"exit-{process.returncode}"
        except OSError as error:
            status = f"not-runnable: {error}"
        finally:
            if process is not None:
                _terminate_process_group(process, kill_grace)
                if process.poll() is None:
                    process.wait()
    text = last_message.read_text(encoding="utf-8", errors="replace") if last_message.exists() else ""
    payload = extract_findings(text) or extract_findings(stdout_path.read_text(encoding="utf-8", errors="replace"))
    if payload is not None:
        (agent_dir / "findings.json").write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    elif status == "completed":
        status = "unparsable"
    return {
        "agent": agent["id"],
        "reviewer": reviewer,
        "attempt": attempt,
        "status": status,
        "seconds": round((datetime.datetime.now() - started).total_seconds()),
        "findings": None if payload is None else len(payload["findings"]),
        "progress": str(progress_path.relative_to(run_dir)),
    }


def command_detect(args: argparse.Namespace, repo: Path) -> int:
    run_dir = Path(args.run_dir).resolve()
    plan = load_plan(run_dir)
    if not plan.get("required"):
        raise RunError("the plan did not require refactoring-review; nothing to detect")
    head = resolve(repo, "HEAD")
    if head != plan["head"]:
        raise RunError(f"detectors read the working tree, which is at {head[:12]}, not the plan head {plan['head'][:12]}")
    before = git(repo, "status", "--porcelain")
    if dirty_code_paths(repo, run_rules(run_dir)):
        raise RunError("detectors read the working tree; commit or stash its code changes first")
    only = {item for item in (args.only or "").split(",") if item}
    agents = [agent for agent in plan["agents"] if not only or agent["id"] in only]
    unknown = only - {agent["id"] for agent in plan["agents"]}
    if unknown:
        raise RunError(f"--only names unknown detectors: {', '.join(sorted(unknown))}")
    choices = {agent["id"]: agent.get("reviewer") or args.reviewer for agent in agents}
    missing = sorted({name for name in choices.values() if name not in REVIEWERS})
    if missing:
        raise RunError(f"no runner for reviewer(s) {', '.join(missing)}; known: {', '.join(sorted(REVIEWERS))}")
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        futures = [pool.submit(run_detector, agent, choices[agent["id"]], repo, run_dir, args.timeout, args.kill_grace) for agent in agents]
        results = [future.result() for future in futures]
    after = git(repo, "status", "--porcelain")
    snapshot_modified = after != before
    if snapshot_modified:
        for result in results:
            result["status"] = "snapshot-modified"
    log = run_dir / "detect.jsonl"
    with log.open("a", encoding="utf-8") as handle:
        for result in results:
            handle.write(json.dumps(result) + "\n")
    print(json.dumps({"detectors": results, "snapshotModified": snapshot_modified}, indent=2))
    return 0 if all(result["status"] == "completed" for result in results) else 1


# ---------------------------------------------------------------------------- merge


def normalize(text: str) -> str:
    return re.sub(r"[^a-z0-9]+", " ", text.lower()).strip()


def load_plan(run_dir: Path) -> dict:
    try:
        return json.loads((run_dir / "plan.json").read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunError(f"cannot read {run_dir}/plan.json: {error}") from error


def run_rules(run_dir: Path) -> dict:
    try:
        return json.loads((run_dir / "rules" / CONFIG_PATH).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RunError(f"cannot read the run's copy of {CONFIG_PATH}: {error}") from error


def validate_finding(finding: object) -> str | None:
    if not isinstance(finding, dict):
        return "finding is not an object"
    for name in REQUIRED_FIELDS:
        if not isinstance(finding.get(name), str) or not finding[name].strip():
            return f"missing or empty {name}"
    if finding["tier"] not in TIERS:
        return f"tier {finding['tier']} is not one of {', '.join(TIERS)}"
    if finding.get("scope", "in-diff") not in SCOPES:
        return f"scope {finding['scope']} is not one of {', '.join(SCOPES)}"
    return None


def merge_findings(plan: dict, config: dict, rules_dir: Path, run_dir: Path) -> dict:
    lens_by_id = {lens["id"]: lens for lens in config["lenses"]}
    known: dict[str, set[str]] = {}
    for lens_id, lens in lens_by_id.items():
        path = rules_dir / RULES_DIR / lens["file"]
        known[lens_id] = set(rule_ids(path.read_text(encoding="utf-8"))) if path.exists() else set()

    agents = []
    merged: dict[tuple[str, str, str], dict] = {}
    for agent in plan["agents"]:
        record = {"id": agent["id"], "status": "completed", "error": None, "findings": 0, "budgetDropped": 0, "invalid": []}
        agents.append(record)
        path = run_dir / "agents" / agent["id"] / "findings.json"
        if not path.exists():
            record.update(status="missing", error=f"{path.name} not found")
            continue
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            record.update(status="invalid", error=f"invalid JSON: {error}")
            continue
        findings = payload.get("findings") if isinstance(payload, dict) else payload
        if not isinstance(findings, list):
            record.update(status="invalid", error="no findings array")
            continue
        allowed = set().union(*(known.get(lens, set()) for lens in agent["lenses"])) | {DEFECT_RULE}
        valid = []
        for index, finding in enumerate(findings):
            problem = validate_finding(finding)
            if problem:
                record["invalid"].append({"index": index, "error": problem})
            else:
                valid.append(finding)
        record["budgetDropped"] = max(0, len(valid) - agent["budget"])
        valid = valid[: agent["budget"]]
        record["findings"] = len(valid)
        for finding in valid:
            flags = []
            if finding["rule"] not in allowed:
                flags.append("unknown-rule")
            if finding["rule"] != DEFECT_RULE and finding["file"] not in agent["files"]:
                flags.append("outside-agent-files")
            key = (finding["file"], finding["symbol"], normalize(finding["refactoring"]))
            entry = merged.get(key)
            if entry is None:
                entry = merged[key] = {
                    "file": finding["file"],
                    "symbol": finding["symbol"],
                    "refactoring": finding["refactoring"],
                    "rules": [],
                    "tier": finding["tier"],
                    "scope": finding.get("scope", "in-diff"),
                    "rationales": [],
                    "behaviorRisks": [],
                    "guardingTests": [],
                    "mechanizable": False,
                    "sources": [],
                    "flags": [],
                }
            entry["tier"] = max(entry["tier"], finding["tier"], key=TIERS.index)
            if finding.get("scope", "in-diff") == "in-diff":
                entry["scope"] = "in-diff"
            for name, value in (("rules", finding["rule"]), ("rationales", finding["rationale"])):
                if value not in entry[name]:
                    entry[name].append(value)
            risk = finding.get("behaviorRisk")
            if isinstance(risk, str) and risk.strip() and risk not in entry["behaviorRisks"]:
                entry["behaviorRisks"].append(risk)
            tests = finding.get("guardingTests") or []
            if isinstance(tests, list):
                entry["guardingTests"] = sorted(set(entry["guardingTests"]) | {t for t in tests if isinstance(t, str)})
            entry["mechanizable"] = entry["mechanizable"] or finding.get("mechanizable") is True
            entry["sources"].append({"agent": agent["id"], "rule": finding["rule"]})
            entry["flags"] = sorted(set(entry["flags"]) | set(flags))

    findings = list(merged.values())
    for index, entry in enumerate(findings, start=1):
        entry["id"] = f"R{index}"
    by_symbol: dict[tuple[str, str], list[str]] = {}
    for entry in findings:
        by_symbol.setdefault((entry["file"], entry["symbol"]), []).append(entry["id"])
    conflicts = [
        {"file": file, "symbol": symbol, "ids": ids} for (file, symbol), ids in by_symbol.items() if len(ids) > 1
    ]
    return {
        "agents": agents,
        "findings": findings,
        "conflicts": conflicts,
        "uncovered": plan.get("uncovered", []),
        "totals": {
            "findings": len(findings),
            "byTier": {tier: sum(1 for f in findings if f["tier"] == tier) for tier in TIERS},
            "incompleteAgents": sum(1 for a in agents if a["status"] != "completed"),
        },
    }


def command_merge(args: argparse.Namespace, repo: Path) -> int:
    del repo
    run_dir = Path(args.run_dir)
    plan = load_plan(run_dir)
    if not plan.get("required"):
        raise RunError("the plan did not require refactoring-review; nothing to merge")
    result = merge_findings(plan, run_rules(run_dir), run_dir / "rules", run_dir)
    (run_dir / "merged.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"mergedJson": str(run_dir / "merged.json"), **result["totals"], "agents": result["agents"]}, indent=2))
    return 1 if result["totals"]["incompleteAgents"] else 0


# ---------------------------------------------------------------------------- verify


def command_verify(args: argparse.Namespace, repo: Path) -> int:
    run_dir = Path(args.run_dir)
    plan = load_plan(run_dir)
    config = run_rules(run_dir)
    behavior_head = plan["head"]
    head = resolve(repo, args.head)
    files = changed_files(repo, behavior_head, head, config)
    code = [f for f in files if f.cls is not None and f.code_lines > 0]
    refactor_lines = sum(f.code_lines for f in code)
    behavior_lines = plan["totals"]["codeLines"]
    ratio = refactor_lines / behavior_lines if behavior_lines else 0.0
    limit = config["limits"]["refactorGrowthRatio"]
    oracle = re.compile(config["oracleLinePattern"])
    oracle_edits = [
        {"path": f.path, "line": text}
        for f in code
        if f.cls == "test"
        for text in f.changed_code
        if oracle.search(text)
    ]
    subjects = git(repo, "log", "--format=%s", f"{behavior_head}..{head}").splitlines()
    non_refactor = [subject for subject in subjects if not subject.startswith("refactor:")]
    result = {
        "behaviorHead": behavior_head,
        "head": head,
        "refactorCodeLines": refactor_lines,
        "behaviorCodeLines": behavior_lines,
        "growthRatio": round(ratio, 4),
        "growthLimit": limit,
        "growthExceeded": ratio > limit,
        "oracleEdits": oracle_edits,
        "nonRefactorCommits": non_refactor,
    }
    print(json.dumps(result, indent=2))
    return 1 if result["growthExceeded"] or oracle_edits or non_refactor else 0


# ---------------------------------------------------------------------------- entry point


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    root.add_argument("--repo", default=".", help="repository root (default: current directory)")
    commands = root.add_subparsers(dest="command", required=True)

    plan = commands.add_parser("plan", help="gate the change and write the detector plan")
    plan.add_argument("--base", help="base ref; the merge-base with --head is used (default: config defaultBase)")
    plan.add_argument("--head", default="HEAD", help="behavior head commit (default: HEAD)")
    plan.add_argument("--output-dir", help="run directory (default: under config reportDir)")
    plan.add_argument("--reviewers", help="comma-separated reviewer names to rotate across detectors")
    plan.add_argument("--allow-dirty", action="store_true", help="plan even with uncommitted code changes")
    plan.add_argument("--force", action="store_true", help="overwrite an existing plan.json")

    detect = commands.add_parser("detect", help="run each planned detector through a read-only reviewer CLI")
    detect.add_argument("run_dir")
    detect.add_argument("--reviewer", default="kimi", help="reviewer for detectors the plan left unassigned")
    detect.add_argument("--concurrency", type=int, default=3, help="detectors running at once (default: 3)")
    detect.add_argument("--timeout", type=int, default=1500, help="seconds per detector (default: 1500)")
    detect.add_argument("--kill-grace", type=int, default=10, help="seconds to wait after SIGTERM before SIGKILL")
    detect.add_argument("--only", help="comma-separated detector ids to (re)run")

    merge = commands.add_parser("merge", help="validate and merge detector findings")
    merge.add_argument("run_dir")

    verify = commands.add_parser("verify", help="check refactor commits against the stop rules and test oracle")
    verify.add_argument("run_dir")
    verify.add_argument("--head", default="HEAD", help="head after the refactor commits (default: HEAD)")
    return root


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    repo = Path(args.repo)
    handlers = {"plan": command_plan, "detect": command_detect, "merge": command_merge, "verify": command_verify}
    try:
        return handlers[args.command](args, repo)
    except RunError as error:
        print(f"refactoring-review: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
