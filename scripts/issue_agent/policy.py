from __future__ import annotations

import dataclasses
import re
from typing import Iterable, List, Mapping, Optional, Sequence, Tuple

from . import labels

TYPES = tuple(labels.TYPE_LABELS)
PRIORITIES = tuple(labels.PRIORITY_LABELS)
ROUTES = ("agent", "human")
REASONS = ("none", "direction", "public-api", "sensitive", "needs-info", "resolved", "duplicate", "external")
SIZES = ("s", "m", "l")
PRIORITY_RANK = {"p0": 0, "p1": 1, "p2": 2}
TITLE_RE = re.compile(r"^(feat|fix|refactor|docs|test|perf|build|ci|chore)(\([a-z0-9-]+\))?!?: [a-z0-9`].{2,}$")
DEV_LINE_RE = re.compile(r"^dev/v(\d+)\.(\d+)\.(\d+)$")
FORBIDDEN_RE = re.compile(r"(^|/)(\.env[^/]*|[^/]*\.(pem|key|p12|jks|gpg|asc)|id_[a-z0-9]+|credentials[^/]*)$")
SECRET_RE = re.compile(
    r"gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sk-[A-Za-z0-9_-]{24,}"
    r"|AKIA[0-9A-Z]{16}|xox[abprs]-[A-Za-z0-9-]{10,}|-----BEGIN [A-Z ]*PRIVATE KEY-----"
)
HUMAN_PREFIXES = (".github/", ".mvn/", "scripts/", "adr/")
LOCAL_ONLY_PREFIXES = (".github/workflows/", ".github/actions/")
HUMAN_SUFFIXES = ("AGENTS.md", "CLAUDE.md")
HUMAN_FILES = frozenset({"pom.xml", "demo/pom.xml", "LICENSE", "design/issue-automation.md"})
BRANCH_PREFIX = "auto/issue-"


@dataclasses.dataclass
class Verdict:
    type: str
    priority: str
    route: str
    reason: str
    size: str
    summary: str
    plan: str
    areas: str
    duplicate_of: int


@dataclasses.dataclass
class Candidate:
    number: int
    priority: str
    authorized: bool


def _pick(value, allowed: Sequence[str], fallback: str) -> str:
    return value if isinstance(value, str) and value in allowed else fallback


def clip(text: str, limit: int) -> str:
    text = text.strip()
    if len(text) <= limit:
        return text
    cut = text[:limit].rsplit(" ", 1)[0].rstrip(",;:")
    return cut + " …"


def _text(value, limit: int) -> str:
    return clip(value, limit) if isinstance(value, str) else ""


def validate_verdict(raw: Mapping, trusted: bool) -> Verdict:
    route = _pick(raw.get("route"), ROUTES, "human")
    reason = _pick(raw.get("reason"), REASONS, "direction")
    if route == "agent":
        reason = "none"
    elif reason == "none":
        reason = "direction"
    duplicate = raw.get("duplicate_of")
    verdict = Verdict(
        type=_pick(raw.get("type"), TYPES, "question"),
        priority=_pick(raw.get("priority"), PRIORITIES, "p2"),
        route=route,
        reason=reason,
        size=_pick(raw.get("size"), SIZES, "m"),
        summary=_text(raw.get("summary"), 1500),
        plan=_text(raw.get("plan"), 4000),
        areas=_text(raw.get("areas"), 800),
        duplicate_of=duplicate if type(duplicate) is int and duplicate > 0 else 0,
    )
    if not trusted:
        verdict = dataclasses.replace(verdict, route="human", reason="external", summary="", plan="", areas="")
    return verdict


def desired_labels(verdict: Verdict) -> dict:
    return {
        "type": labels.TYPE_LABELS[verdict.type],
        "priority": labels.PRIORITY_LABELS[verdict.priority],
        "route": labels.ELIGIBLE if verdict.route == "agent" else labels.NEEDS_DECISION,
    }


def priority_of(issue_labels: Iterable[str]) -> Optional[str]:
    present = set(issue_labels)
    ranked = [p for p, label in labels.PRIORITY_LABELS.items() if label in present]
    return min(ranked, key=PRIORITY_RANK.__getitem__) if ranked else None


def select_candidates(issues: Iterable, auto_priorities: Iterable[str]) -> List[Candidate]:
    allowed = set(auto_priorities)
    picked = []
    for issue in issues:
        present = set(issue.labels)
        if present & labels.VETOES:
            continue
        priority = priority_of(present)
        authorized = labels.READY in present and issue.ready_authorized
        automatic = (
            labels.ELIGIBLE in present
            and priority in allowed
            and issue.trusted_author
            and issue.triage_current
        )
        if authorized or automatic:
            picked.append(Candidate(issue.number, priority or "p2", authorized))
    rank = lambda c: (PRIORITY_RANK.get(c.priority, 3), not c.authorized, c.number)
    return sorted(picked, key=rank)


def classify_paths(changes: Iterable[Tuple[str, str]]) -> Tuple[List[str], List[str]]:
    forbidden, human = [], []
    for status, path in changes:
        if FORBIDDEN_RE.search(path) or (path.startswith("adr/") and status != "A"):
            forbidden.append(path)
        elif (status[:1] in ("D", "R") or path.startswith(HUMAN_PREFIXES) or path in HUMAN_FILES
              or path.endswith(HUMAN_SUFFIXES)):
            human.append(f"{status} {path}")
    return forbidden, human


def local_only_paths(changes: Iterable[Tuple[str, str]]) -> List[str]:
    return sorted({path for _, path in changes if path.startswith(LOCAL_ONLY_PREFIXES)})


def valid_title(title: str) -> bool:
    return bool(TITLE_RE.match(title)) and len(title) <= 100


def newest_dev_line(branches: Iterable[str]) -> Optional[str]:
    versioned = [(tuple(int(g) for g in m.groups()), b) for b in branches for m in [DEV_LINE_RE.match(b)] if m]
    return max(versioned)[1] if versioned else None


def branch_name(number: int, title: str, run_id: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", title.lower()).strip("-")[:24].rstrip("-")
    return f"{BRANCH_PREFIX}{number}-{slug or 'work'}-{run_id}"
