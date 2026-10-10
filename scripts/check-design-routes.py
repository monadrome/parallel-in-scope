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
PROPOSAL_RE = re.compile(r"(P[0-9]{4})-[a-z0-9]+(?:-[a-z0-9]+)*\.md")
BACK_REFERENCE_RE = re.compile(r"\bproposal:(P[0-9]{4})(?![a-zA-Z0-9])")
SUMMARY_RE = re.compile(r"^## 蒸馏摘要\s*\n(.*?)(?=^#{1,2} |\Z)", re.MULTILINE | re.DOTALL)
FENCE_RE = re.compile(r"^ {0,3}(`{3,}|~{3,})(.*)$")
COMMENT_RE = re.compile(r"<!--.*?(?:-->|\Z)", re.DOTALL)
INLINE_CODE_RE = re.compile(r"(`+)(?!`).*?(?<!`)\1(?!`)")


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


def unfenced_lines(text: str):
    fence = None
    for line in COMMENT_RE.sub("", text).splitlines():
        match = FENCE_RE.match(line)
        if fence is not None:
            if (match and match[1][0] == fence[0] and len(match[1]) >= len(fence)
                    and not match[2].strip()):
                fence = None
            continue
        if match:
            fence = match[1]
            continue
        yield line


def route_tables(text: str) -> dict[str, dict[PurePosixPath, str]]:
    tables = {"活契约": {}, "在途提案": {}}
    section = None
    for line in unfenced_lines(text):
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
    for target, raw in sorted(targets.items()):
        if not (ROUTE_DOC.parent / Path(*target.parts)).exists():
            errors.append(f"design/AGENTS.md: missing target: {raw}")
    for section, documents in (("活契约", live_documents), ("在途提案", proposal_documents)):
        errors.extend(check_routes(documents, tables[section], section == "在途提案"))
    proposals_by_id = {}
    for document in sorted(proposal_documents):
        match = PROPOSAL_RE.fullmatch(document.name) if len(document.parts) == 2 else None
        if not match:
            errors.append(f"design/{document}: invalid proposal filename")
        else:
            proposal_id = match[1]
            if proposal_id in proposals_by_id:
                errors.append(f"design/{document}: duplicate proposal id: {proposal_id}")
            proposals_by_id[proposal_id] = document
        content = (ROUTE_DOC.parent / document).read_text(encoding="utf-8")
        summary = SUMMARY_RE.search("\n".join(unfenced_lines(content)))
        if not summary or not summary[1].strip():
            errors.append(f"design/{document}: missing or empty distillation summary")
    live_documents.update(
        target for target in tables["活契约"]
        if len(target.parts) == 1 and target.suffix == ".md" and target.name != "AGENTS.md"
    )
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
