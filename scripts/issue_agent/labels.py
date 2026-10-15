from __future__ import annotations

from typing import Dict, Iterable, Mapping, Set, Tuple

PRIORITY_LABELS = {"p0": "priority/p0", "p1": "priority/p1", "p2": "priority/p2"}
TYPE_LABELS = {
    "bug": "bug",
    "documentation": "documentation",
    "enhancement": "enhancement",
    "question": "question",
}
ELIGIBLE = "agent/eligible"
READY = "agent/ready"
WORKING = "agent/working"
REVIEW = "agent/review"
BLOCKED = "agent/blocked"
NEEDS_DECISION = "needs-decision"
DUPLICATE = "duplicate"

FAMILIES = {
    "type": frozenset(TYPE_LABELS.values()),
    "priority": frozenset(PRIORITY_LABELS.values()),
    "route": frozenset({ELIGIBLE, NEEDS_DECISION}),
}
VETOES = frozenset({NEEDS_DECISION, BLOCKED, WORKING, REVIEW})
IN_FLIGHT = frozenset({WORKING, REVIEW})

DEFINITIONS = (
    ("priority/p0", "b60205", "Breaks users or blocks the dev line; work it first"),
    ("priority/p1", "d93f0b", "Should land on the current dev line"),
    ("priority/p2", "fbca04", "Worth doing when capacity allows"),
    (NEEDS_DECISION, "5319e7", "Waits for a maintainer decision; the agent runner skips it"),
    (ELIGIBLE, "c2e0c6", "Triage judged it fit for unattended agent work"),
    (READY, "0e8a16", "A maintainer authorized the agent runner to work it until the issue closes"),
    (WORKING, "1d76db", "Claimed by the agent runner"),
    (REVIEW, "0052cc", "Agent PR passed its gates and waits for a maintainer merge"),
    (BLOCKED, "e99695", "Agent run stopped; remove this label to let the runner retry"),
)


def reconcile(
    current: Iterable[str],
    desired: Mapping[str, str],
    previous: Mapping[str, str],
    suppressed: Iterable[str] = (),
) -> Tuple[Set[str], Set[str], Dict[str, str], Set[str]]:
    present_labels = set(current)
    suppressed_labels = set(suppressed)
    add: Set[str] = set()
    remove: Set[str] = set()
    applied: Dict[str, str] = {}
    for family, members in FAMILIES.items():
        owned = previous.get(family)
        if owned and owned not in present_labels:
            suppressed_labels.add(owned)
            owned = None
        present = present_labels & members
        if present - {owned}:
            continue
        want = desired.get(family)
        if want is None or want in suppressed_labels:
            if owned:
                remove.add(owned)
            continue
        if owned and owned != want:
            remove.add(owned)
        if want not in present_labels:
            add.add(want)
        applied[family] = want
    return add, remove, applied, suppressed_labels
