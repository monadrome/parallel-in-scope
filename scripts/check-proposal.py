#!/usr/bin/env python3
"""Check proposal identity, abstracts, and content bans.

In-flight proposals live in ``design/proposal/`` and archived dormant proposals in
``design/archive/proposal/``. Both locations are checked for filename shape and id
uniqueness, so an archived id blocks reuse. In-flight proposals additionally need a
non-empty abstract as a real section and must not carry line-number
citations; archived files are checked for identity only. Line counts are printed as
a review signal and never fail the gate.
"""

from pathlib import Path
import re
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
from proposal_md import NAME_RE, SUMMARY_RE, unfenced_lines  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PROPOSAL_ROOT = ROOT / "design" / "proposal"
ARCHIVE_ROOT = ROOT / "design" / "archive" / "proposal"

CLASS_LINE_RE = re.compile(r"(?<![\w./-])[A-Z][A-Za-z0-9_]*:\d+(?:[-,]\d+)*")
STATUS_SECTION_RE = re.compile(r"^## (?:状态|Status)\s*$")


def check_active(label: str, text: str) -> list[str]:
    errors = []
    visible = list(unfenced_lines(text))
    match = SUMMARY_RE.search("\n".join(line for _, line in visible))
    if match is None:
        errors.append(f"{label}: missing abstract section")
    elif not match.group(1).strip():
        errors.append(f"{label}: empty abstract section")
    for number, line in visible:
        if CLASS_LINE_RE.search(line):
            errors.append(f"{label}:{number}: line-number citation: {line.strip()}")
        if STATUS_SECTION_RE.match(line):
            errors.append(f"{label}:{number}: status section not allowed: {line.strip()}")
    return errors


def main() -> int:
    errors = []
    counts = []
    ids = {}
    for root, active in ((PROPOSAL_ROOT, True), (ARCHIVE_ROOT, False)):
        if not root.is_dir():
            continue
        for document in sorted(root.glob("*.md")):
            label = document.relative_to(ROOT).as_posix()
            text = document.read_text(encoding="utf-8")
            match = NAME_RE.match(document.name)
            if match is None:
                errors.append(f"{label}: invalid proposal filename")
            else:
                proposal_id = match.group(1)
                if proposal_id in ids:
                    errors.append(
                        f"{label}: duplicate proposal id: P{proposal_id}"
                        f" (also {ids[proposal_id]})"
                    )
                else:
                    ids[proposal_id] = label
            if active:
                errors.extend(check_active(label, text))
            counts.append((label, len(text.splitlines())))
    if errors:
        print("Proposal conformance failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Proposal conformance: OK")
    if counts:
        print("Line counts (review signal, not a gate):")
        print("\n".join(f"- {label}: {count}" for label, count in counts))
    return 0


if __name__ == "__main__":
    sys.exit(main())
