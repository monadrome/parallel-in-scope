#!/usr/bin/env python3
"""Check ADR metadata fields and required sections."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
ADR_ROOT = ROOT / "adr"

STATUSES = {"Proposed", "Accepted", "Deprecated", "Superseded"}
STABILITIES = {"stable", "beta"}
CATEGORIES = {"core", "non-core"}
REQUIRED_FIELDS = ("Status", "Date", "Decision scope", "Stability", "Category", "Supersedes")
REQUIRED_SECTIONS = ("Context", "Decision", "Alternatives Considered", "Consequences")
DATE_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
FIELD_RE = re.compile(r"^- ([A-Za-z][A-Za-z ]*?):\s*(\S.*)$")
SECTION_RE = re.compile(r"^## (.+?)\s*$")
HEADER_LINES = 40


def check_document(path: Path) -> list[str]:
    errors = []
    lines = path.read_text(encoding="utf-8").splitlines()
    fields = {}
    for line in lines[:HEADER_LINES]:
        if line.startswith(">") or line.startswith("## "):
            break
        match = FIELD_RE.match(line)
        if match:
            fields.setdefault(match.group(1), match.group(2).strip())
    for field in REQUIRED_FIELDS:
        if field not in fields:
            errors.append(f"adr/{path.name}: missing metadata field: {field}")
    status = fields.get("Status")
    if status is not None and status not in STATUSES:
        errors.append(f"adr/{path.name}: invalid Status: {status}")
    date = fields.get("Date")
    if date is not None and not DATE_RE.match(date):
        errors.append(f"adr/{path.name}: Date is not YYYY-MM-DD: {date}")
    stability = fields.get("Stability")
    if stability is not None and stability not in STABILITIES:
        errors.append(f"adr/{path.name}: invalid Stability: {stability}")
    category = fields.get("Category")
    if category is not None and category not in CATEGORIES:
        errors.append(f"adr/{path.name}: invalid Category: {category}")
    sections = {match.group(1) for line in lines if (match := SECTION_RE.match(line))}
    for section in REQUIRED_SECTIONS:
        if section not in sections:
            errors.append(f"adr/{path.name}: missing required section: {section}")
    return errors


def main() -> int:
    errors = []
    if ADR_ROOT.is_dir():
        for document in sorted(ADR_ROOT.glob("[0-9][0-9][0-9][0-9]-*.md")):
            errors.extend(check_document(document))
    if errors:
        print("ADR conformance failed:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("ADR conformance: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
