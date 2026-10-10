#!/usr/bin/env python3
"""Fail a pull request that removes or changes public API without migration notes.

The surface is every public and protected member of every public type, nested types
included, read from `javap -protected -v` over two compiled class trees: the pull
request's merge base and its head. Each member becomes one normalized line, so member
order and classfile noise never register; a changed signature is a removed line plus an
added line. Removed lines fail the check unless the same pull request changes both
docs/en/migration-v<major>.<minor>.md and docs/zh/migration-v<major>.<minor>.md, where
<major>.<minor> comes from the head pom.xml version. Added lines are listed, never fatal.

The report goes to stdout and, when set, $GITHUB_STEP_SUMMARY. Exit status: 0 pass,
1 removals without migration notes, 2 the check could not run.
"""

from __future__ import annotations

import argparse
import dataclasses
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import Iterable, Sequence

ROOT = Path(__file__).resolve().parents[1]
GUIDE_DIRS = ("docs/en", "docs/zh")
LINE_RE = re.compile(r"^(\d+)\.(\d+)(?:\.|-|$)")
SYNTHETIC_CLASS_RE = re.compile(r"\$\d")
SKIPPED_CLASS_FILES = frozenset({"package-info.class", "module-info.class"})
FLAG_RE = re.compile(r"\bACC_[A-Z]+\b")
INNER_CLASS_RE = re.compile(
    r"^  (?P<modifiers>(?:[a-z]+ )*)#\d+= #\d+ of #\d+;\s+// "
    r"[\w$]+=class (?P<inner>\S+) of class (?P<outer>\S+)$"
)
VISIBLE = frozenset({"ACC_PUBLIC", "ACC_PROTECTED"})
COMPILER_GENERATED = frozenset({"ACC_SYNTHETIC", "ACC_BRIDGE"})
IGNORED_MODIFIERS = frozenset({"synchronized", "native", "strictfp"})
OBJECT_SUPERCLASS = "extends java.lang.Object"


class ApiSurfaceError(Exception):
    """The check could not produce a trustworthy answer."""


@dataclasses.dataclass
class ClassFile:
    name: str
    declaration: str
    flags: frozenset[str]
    nesting: dict[str, tuple[str, str]]
    members: list[tuple[str, frozenset[str]]]


def access_flags(line: str) -> frozenset[str]:
    return frozenset(FLAG_RE.findall(line))


def dotted(internal_name: str) -> str:
    return internal_name.replace("/", ".")


def parse_javap(text: str) -> list[ClassFile]:
    """Split one `javap -protected -v` run into its classfile blocks and parse each."""
    blocks: list[list[str]] = []
    for line in text.splitlines():
        if line.startswith("Classfile "):
            blocks.append([])
        elif blocks:
            blocks[-1].append(line)
        elif line.strip():
            raise ApiSurfaceError(f"unrecognized javap output before the first classfile: {line!r}")
    return [parse_class(block) for block in blocks]


def parse_class(lines: Sequence[str]) -> ClassFile:
    name = declaration = pending = ""
    flags: frozenset[str] = frozenset()
    nesting: dict[str, tuple[str, str]] = {}
    members: list[tuple[str, frozenset[str]]] = []
    section = "header"
    for line in lines:
        if section == "header":
            if line == "Constant pool:":
                section = "constants"
            elif line.strip() and not line.startswith(" "):
                if declaration:
                    raise ApiSurfaceError(f"unrecognized javap class header line: {line!r}")
                declaration = " ".join(line.split())
            elif line.startswith("  flags: "):
                flags = access_flags(line)
            elif line.startswith("  this_class: ") and "//" in line:
                name = dotted(line.split("//", 1)[1].strip())
        elif section == "constants":
            if line == "{":
                section = "members"
        elif section == "members":
            declares = line.startswith("  ") and not line.startswith("   ")
            if (declares or line == "}") and pending:
                raise ApiSurfaceError(f"no flags line for member {pending!r} of {name}")
            if line == "}":
                section = "attributes"
            elif declares:
                pending = line.strip()
            elif pending and line.startswith("    flags: "):
                members.append((pending, access_flags(line)))
                pending = ""
        elif line == "InnerClasses:":
            section = "inner-classes"
        elif section == "inner-classes" and line.startswith("  "):
            match = INNER_CLASS_RE.match(line)
            if not match:
                raise ApiSurfaceError(f"unrecognized javap InnerClasses entry of {name}: {line!r}")
            nesting[dotted(match["inner"])] = (match["modifiers"].strip(), dotted(match["outer"]))
        else:
            section = "attributes"
    if not (name and declaration and flags) or section in ("header", "constants", "members"):
        raise ApiSurfaceError(f"unrecognized javap output for class {name or '<unknown>'}")
    return ClassFile(name, declaration, flags, nesting, members)


def is_api(name: str, classes: dict[str, ClassFile]) -> bool:
    """A type is API when it and every enclosing type are public.

    A nested type's own header carries ACC_PUBLIC even when it is declared protected; its
    InnerClasses entry is the declared access, so that entry decides.
    """
    current = classes.get(name)
    if current is None or "ACC_PUBLIC" not in current.flags:
        return False
    if name not in current.nesting:
        return True
    modifiers, outer = current.nesting[name]
    return "public" in modifiers.split() and is_api(outer, classes)


def top_level_words(text: str) -> list[str]:
    words, depth, start = [], 0, 0
    for index, char in enumerate(text):
        depth += (char == "<") - (char == ">")
        if char == " " and depth == 0:
            words.append(text[start:index])
            start = index + 1
    words.append(text[start:])
    return [word for word in words if word]


def type_parts(declaration: str) -> list[str]:
    """The type head plus one line per direct supertype, so adding an interface is an addition."""
    words = top_level_words(declaration)
    split = next((i for i, word in enumerate(words) if word in ("extends", "implements")), len(words))
    parts, keyword = [" ".join(words[:split])], ""
    for word in words[split:]:
        if word in ("extends", "implements"):
            keyword = word
        else:
            parts.append(f"{keyword} {word.rstrip(',')}")
    return [part for part in parts if part != OBJECT_SUPERCLASS]


def member_signature(declaration: str) -> str:
    words = declaration.rstrip(";").split()
    return " ".join(word for word in words if word not in IGNORED_MODIFIERS)


def surface(classes: Iterable[ClassFile]) -> set[str]:
    """One normalized line per public type head, direct supertype, and visible member."""
    by_name = {c.name: c for c in classes}
    lines = set()
    for name, current in by_name.items():
        if not is_api(name, by_name):
            continue
        lines.update(f"{name}: {part}" for part in type_parts(current.declaration))
        for declaration, flags in current.members:
            if flags & VISIBLE and not flags & COMPILER_GENERATED:
                lines.add(f"{name}: {member_signature(declaration)}")
    return lines


def class_files(tree: Path) -> list[Path]:
    """Declared types only: no package-info/module-info, no anonymous or local classes."""
    return sorted(
        path
        for path in tree.rglob("*.class")
        if path.name not in SKIPPED_CLASS_FILES and not SYNTHETIC_CLASS_RE.search(path.stem)
    )


def javap_command() -> str:
    java_home = os.environ.get("JAVA_HOME")
    if java_home and (Path(java_home) / "bin" / "javap").is_file():
        return str(Path(java_home) / "bin" / "javap")
    found = shutil.which("javap")
    if not found:
        raise ApiSurfaceError("javap not found; set JAVA_HOME to the build JDK or put javap on PATH")
    return found


def run_javap(files: Sequence[Path]) -> str:
    command = [javap_command(), "-J-Duser.language=en", "-protected", "-v", *map(str, files)]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if result.returncode != 0:
        raise ApiSurfaceError(f"javap failed with exit status {result.returncode}:\n{result.stderr.strip()}")
    return result.stdout


def extract(tree: Path) -> set[str]:
    files = class_files(tree)
    if not files:
        raise ApiSurfaceError(f"no class files under {tree}; build the tree before checking it")
    classes = parse_javap(run_javap(files))
    if len(classes) != len(files):
        raise ApiSurfaceError(f"javap described {len(classes)} of {len(files)} class files under {tree}")
    lines = surface(classes)
    if not lines:
        raise ApiSurfaceError(f"no public API found under {tree}; javap output may have changed format")
    return lines


def diff(base: set[str], head: set[str]) -> tuple[list[str], list[str]]:
    return sorted(base - head), sorted(head - base)


def migration_guides(version: str) -> tuple[str, ...]:
    match = LINE_RE.match(version)
    if not match:
        raise ApiSurfaceError(f"cannot read <major>.<minor> from project version {version!r}")
    name = f"migration-v{match[1]}.{match[2]}.md"
    return tuple(f"{directory}/{name}" for directory in GUIDE_DIRS)


def project_version(pom: bytes) -> str:
    """The project's own <version>, not the parent's."""
    try:
        root = ElementTree.fromstring(pom)
    except ElementTree.ParseError as error:
        raise ApiSurfaceError(f"pom.xml is not well-formed XML: {error}") from error
    namespace = root.tag[: root.tag.index("}") + 1] if root.tag.startswith("{") else ""
    version = (root.findtext(f"{namespace}version") or "").strip()
    if not version:
        raise ApiSurfaceError("pom.xml declares no project <version>")
    return version


def git(*args: str) -> bytes:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise ApiSurfaceError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout


def resolve_commit(revision: str) -> str:
    return git("rev-parse", "--verify", f"{revision}^{{commit}}").decode().strip()


def changed_paths(base: str) -> set[str]:
    listing = git("diff", "--name-only", "--no-renames", "-z", f"{base}..HEAD")
    return {path for path in listing.decode("utf-8", "replace").split("\0") if path}


def exists_at_head(path: str) -> bool:
    return subprocess.run(["git", "cat-file", "-e", f"HEAD:{path}"], cwd=ROOT, capture_output=True).returncode == 0


@dataclasses.dataclass
class Verdict:
    passed: bool
    message: str


def evaluate(removed: Sequence[str], guides: Sequence[str], documented: set[str]) -> Verdict:
    """Removals pass only when every guide changed in this pull request and still exists."""
    if not removed:
        return Verdict(True, "no public API was removed or changed")
    missing = [guide for guide in guides if guide not in documented]
    if not missing:
        return Verdict(True, "public API was removed or changed and both migration guides are updated")
    return Verdict(
        False,
        "public API was removed or changed; update " + " and ".join(guides)
        + " in this pull request (not updated: " + ", ".join(missing) + ")",
    )


def render(base: str, removed: Sequence[str], added: Sequence[str], verdict: Verdict) -> str:
    lines = [
        "## Public API surface",
        "",
        f"Compared with merge base `{base[:12]}`: {len(removed)} removed or changed, {len(added)} added.",
        "",
        f"Result: {'pass' if verdict.passed else 'fail'} - {verdict.message}.",
    ]
    if removed and added:
        lines += ["", "A changed signature is listed by its old form under Removed and its new form under Added."]
    for title, sign, entries in (("Removed or changed", "-", removed), ("Added", "+", added)):
        if entries:
            lines += ["", f"### {title}", "", "```diff", *(f"{sign} {entry}" for entry in entries), "```"]
    return "\n".join(lines) + "\n"


def publish(report: str) -> None:
    print(report, end="")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as handle:
            handle.write(report)


def revision_argument(value: str) -> str:
    if not value or value.startswith("-"):
        raise argparse.ArgumentTypeError(f"not a revision: {value!r}")
    return value


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-classes", type=Path, required=True, help="compiled classes of the merge base")
    parser.add_argument("--head-classes", type=Path, required=True, help="compiled classes of the head")
    parser.add_argument(
        "--base", type=revision_argument, required=True, help="merge-base commit; migration notes count from it to HEAD"
    )
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        base = resolve_commit(args.base)
        guides = migration_guides(project_version(git("show", "HEAD:pom.xml")))
        changed = changed_paths(base)
        removed, added = diff(extract(args.base_classes), extract(args.head_classes))
    except ApiSurfaceError as error:
        print(f"API surface check could not run: {error}", file=sys.stderr)
        return 2
    documented = {guide for guide in guides if guide in changed and exists_at_head(guide)}
    verdict = evaluate(removed, guides, documented)
    publish(render(base, removed, added, verdict))
    return 0 if verdict.passed else 1


if __name__ == "__main__":
    sys.exit(main())
