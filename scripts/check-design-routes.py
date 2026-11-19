#!/usr/bin/env python3
"""Check design routes, proposal identity, and landed-proposal removal."""

from pathlib import Path, PurePosixPath
import re
import subprocess
import sys
from urllib.parse import unquote, urlparse

ROOT = Path(__file__).resolve().parents[1]
ROUTE_DOC = ROOT / "design" / "AGENTS.md"
LINK_RE = re.compile(r"\[([^\]]*)\]\(([^)\s]+)(?:\s+['\"][^)]*['\"])?\)")
BACK_REFERENCE_RE = re.compile(r"\bproposal:(P[0-9]{4})(?![a-zA-Z0-9])")
INLINE_CODE_RE = re.compile(r"(`+)(?!`).*?(?<!`)\1(?!`)")

sys.path.insert(0, str(Path(__file__).resolve().parent))
from proposal_md import NAME_RE, SUMMARY_RE, unfenced_lines  # noqa: E402
BETA_TAG = "[beta]"
BETA_MARKER_RE = re.compile(r"^>\s*Stability:\s*beta\b", re.MULTILINE)


def route_targets(text: str) -> dict[PurePosixPath, str]:
    """Map of link targets (relative to design/) to their raw form."""
    targets = {}
    for raw_target in LINK_RE.findall(text):
        raw = unquote(raw_target[1])
        parsed = urlparse(raw)
        if parsed.scheme or parsed.netloc or raw.startswith("#"):
            continue
        targets[PurePosixPath(parsed.path)] = raw
    return targets


def route_tables(text: str) -> dict[str, dict[PurePosixPath, str]]:
    tables = {"Live contracts": {}, "In-flight proposals": {}}
    section = None
    for _, line in unfenced_lines(text):
        if line.startswith("## "):
            section = line[3:].strip()
        elif section in tables and line.startswith("|"):
            document_column = INLINE_CODE_RE.sub("", line.split("|", 2)[1])
            tables[section].update(route_targets(document_column))
    return tables


def tracked_live_documents() -> list[str]:
    result = subprocess.run(
        ["git", "ls-files", "design/*.md"],
        cwd=ROOT,
        check=True,
        capture_output=True,
        text=True,
    )
    documents = []
    for relative in result.stdout.splitlines():
        path = PurePosixPath(relative)
        if len(path.parts) == 2 and path.name != "AGENTS.md" and (ROOT / relative).is_file():
            documents.append(path.name)
    return sorted(documents)


def check_routes(documents: set[PurePosixPath], targets: dict[PurePosixPath, str],
                 proposal_section: bool) -> list[str]:
    errors = []
    for document in sorted(documents - targets.keys()):
        errors.append(f"design/{document}: document missing from the route table")
    for target, raw in sorted(targets.items()):
        targets_proposal = target.parts[:1] == ("proposal",)
        if targets_proposal != proposal_section:
            errors.append(f"design/AGENTS.md: target in the wrong route section: {raw}")
    return errors


def beta_routed_documents(text: str) -> set[PurePosixPath]:
    """Live-contract documents whose route-table row is tagged [beta]."""
    documents = set()
    section = None
    for _, line in unfenced_lines(text):
        if line.startswith("## "):
            section = line[3:].strip()
        elif section == "Live contracts" and line.startswith("|") and BETA_TAG in line:
            document_column = INLINE_CODE_RE.sub("", line.split("|", 2)[1])
            documents.update(route_targets(document_column))
    return documents


def check_beta_markers(text: str, documents: set[PurePosixPath]) -> list[str]:
    errors = []
    tagged = beta_routed_documents(text)
    for document in sorted(documents):
        path = ROUTE_DOC.parent / document
        if not path.is_file():
            continue
        has_marker = BETA_MARKER_RE.search(path.read_text(encoding="utf-8")) is not None
        if document in tagged and not has_marker:
            errors.append(
                f"design/{document}: route row is tagged {BETA_TAG} "
                "but the document has no beta stability marker"
            )
        if has_marker and document not in tagged:
            errors.append(
                f"design/{document}: beta stability marker without a {BETA_TAG} route tag"
            )
    return errors


def check_adr_ids() -> list[str]:
    adr_root = ROOT / "adr"
    if not adr_root.is_dir():
        return []
    seen: dict[str, Path] = {}
    errors = []
    for document in sorted(adr_root.glob("[0-9][0-9][0-9][0-9]-*.md")):
        adr_id = document.name[:4]
        previous = seen.get(adr_id)
        if previous is not None:
            errors.append(
                f"adr/{document.name}: duplicate ADR id {adr_id}; "
                f"already used by adr/{previous.name}"
            )
        else:
            seen[adr_id] = document
    return errors


def main() -> int:
    text = ROUTE_DOC.read_text(encoding="utf-8")
    targets = route_targets(text)
    tables = route_tables(text)
    live_documents = {PurePosixPath(name) for name in tracked_live_documents()}
    proposal_documents = {
        PurePosixPath("proposal") / path.relative_to(ROOT / "design" / "proposal").as_posix()
        for path in (ROOT / "design" / "proposal").rglob("*.md")
    }
    errors = []
    errors.extend(check_adr_ids())
    for target, raw in sorted(targets.items()):
        if not (ROUTE_DOC.parent / Path(*target.parts)).exists():
            errors.append(f"design/AGENTS.md: missing target: {raw}")
    for section, documents in (("Live contracts", live_documents), ("In-flight proposals", proposal_documents)):
        errors.extend(check_routes(documents, tables[section], section == "In-flight proposals"))
    proposals_by_id = {}
    for document in sorted(proposal_documents):
        match = NAME_RE.match(document.name) if len(document.parts) == 2 else None
        if not match:
            errors.append(f"design/{document}: invalid proposal filename")
        else:
            proposal_id = f"P{match[1]}"
            if proposal_id in proposals_by_id:
                errors.append(f"design/{document}: duplicate proposal id: {proposal_id}")
            proposals_by_id[proposal_id] = document
        content = (ROUTE_DOC.parent / document).read_text(encoding="utf-8")
        summary = SUMMARY_RE.search("\n".join(line for _, line in unfenced_lines(content)))
        if not summary or not summary[1].strip():
            errors.append(f"design/{document}: missing or empty abstract")
    live_documents.update(
        target for target in tables["Live contracts"]
        if len(target.parts) == 1 and target.suffix == ".md" and target.name != "AGENTS.md"
    )
    errors.extend(check_beta_markers(text, live_documents))
    for document in sorted(live_documents):
        path = ROUTE_DOC.parent / document
        if not path.is_file():
            continue
        for proposal_id in sorted(set(BACK_REFERENCE_RE.findall(path.read_text(encoding="utf-8")))):
            if proposal_id in proposals_by_id:
                errors.append(
                    f"design/{document}: landed proposal {proposal_id} still exists: "
                    f"design/{proposals_by_id[proposal_id]}"
                )
    if errors:
        print("Design route table drift detected:", file=sys.stderr)
        print("\n".join(f"- {error}" for error in errors), file=sys.stderr)
        return 1
    print("Design route table: OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
