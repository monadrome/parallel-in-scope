#!/usr/bin/env python3
"""Fail when a change introduces a violation of the library's written Java conventions.

The rules restate conventions from the root AGENTS.md that review used to enforce alone;
verification/java-conventions.md owns their scope, exemptions, and limitations:

- R1: a public or protected zero-argument `getX()`/`isX()` method without `@Override`;
- R2: a Guava precondition or `Verify` message literal that ends with a period, starts
  uppercase without a leading code identifier, or contains a `%` that does not begin `%s`;
- R3: a single-l spelling (`canceled`, `canceling`, `canceler`, `cancelable`, `signaling`,
  `labeled`, in lowercase, capitalized, or uppercase form, alone or as a camelCase part)
  where the convention is the doubled-l form;
- R4: `Objects.requireNonNull`, where the convention is Guava `checkNotNull`.

Only library sources are scanned: `*.java` under src/main/java, minus the queue package.
The check is diff-scoped by baseline subtraction, not by hunk line numbers. Both the base
tree and the head tree are scanned; every violation becomes a key of relative path, rule,
and normalized source text, and a key fails only when the head holds it more times than
the base. An unchanged pre-existing violation therefore passes wherever it moves, while a
newly added duplicate of it fails.

The base is a git revision (default: the merge base of origin/dev/v0.4.0 and HEAD),
extracted with `git archive`, or a directory given by --base-root. The head is the working
tree's src/main/java unless --head-root names another directory. Violations print as
`path:line: rule: message`. Exit status: 0 pass, 1 new violations, 2 the check could not run.
"""

from __future__ import annotations

import argparse
import collections
import dataclasses
import io
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Iterable, Iterator, Sequence

ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = "src/main/java"
DEFAULT_UPSTREAM = "origin/dev/v0.4.0"
# The queue package keeps its JDK-port parity under queue/AGENTS.md; this gate leaves it alone.
EXCLUDED_PREFIXES = ("io/github/monadrome/parallelinscope/queue/",)

ACCESSOR_NAME = re.compile(r"(?:get|is)[A-Z][\w$]*")
OVERRIDE = re.compile(r"@(?:\w+\.)*Override\b")
METHOD_HEAD = re.compile(r"(?P<head>.*?)\b(?P<name>[A-Za-z_$][\w$]*)\s*$", re.DOTALL)
TYPE_HEADER = re.compile(r"(?:^|[^\w$.])(?:(?P<interface>@?interface)|class|enum)\s+[A-Za-z_$]")
HEAD_LAST_TOKEN = re.compile(r"[\w$>\]]\s*$")

# Message argument index per checked call. checkElementIndex/checkPositionIndex take a
# description, not a template, as their third argument; the same style applies to it.
MESSAGE_CALLS = {
    "checkArgument": 1,
    "checkState": 1,
    "checkNotNull": 1,
    "checkElementIndex": 2,
    "checkPositionIndex": 2,
    "verify": 1,
    "verifyNotNull": 1,
}
MESSAGE_CALL = re.compile(
    r"(?:(?<![\w$.])|(?<=\bPreconditions\.)|(?<=\bVerify\.))(?P<name>" + "|".join(MESSAGE_CALLS) + r")\s*\("
)
LEADING_IDENTIFIER = re.compile(r"[A-Za-z_$][\w.$]*")
CODE_IDENTIFIER = re.compile(r".[^A-Z]*[A-Z]|.*[.$_0-9]")
FOLLOWED_BY_QUOTE = re.compile(r"\s*['\"]")
BAD_PLACEHOLDER = re.compile(r"%(?!s)")

SINGLE_L = re.compile(
    r"(?<![A-Za-z])(?:cancel(?:ed|ing|ers?|able)|signaling|labeled)(?![a-z])"
    r"|(?:Cancel(?:ed|ing|ers?|able)|Signaling|Labeled)(?![a-z])"
    r"|(?<![A-Za-z])(?:CANCEL(?:ED|ING|ERS?|ABLE)|SIGNALING|LABELED)(?![A-Za-z])"
)
DOUBLED_L = {
    "canceled": "cancelled",
    "canceling": "cancelling",
    "canceler": "canceller",
    "cancelers": "cancellers",
    "cancelable": "cancellable",
    "signaling": "signalling",
    "labeled": "labelled",
}
REQUIRE_NON_NULL = re.compile(r"(?<![\w$])requireNonNull\b")


class CheckError(Exception):
    """The check could not produce a trustworthy answer."""


@dataclasses.dataclass(frozen=True)
class Violation:
    path: str
    line: int
    rule: str
    message: str
    key: str


@dataclasses.dataclass(frozen=True)
class Source:
    """One file: the original text, a same-length copy with comments and literal contents
    blanked, and the [start, end) span of every string literal, quotes included."""

    text: str
    code: str
    literals: dict[int, int]

    def line_of(self, offset: int) -> int:
        return self.text.count("\n", 0, offset) + 1

    def line_text(self, offset: int) -> str:
        start = self.text.rfind("\n", 0, offset) + 1
        end = self.text.find("\n", offset)
        return normalize(self.text[start:] if end < 0 else self.text[start:end])


def normalize(text: str) -> str:
    return " ".join(text.split())


def top_level(text: str) -> str:
    """The text outside parentheses and brackets, so annotation arguments do not count."""
    depth, kept = 0, []
    for character in text:
        if character in "([":
            depth += 1
        elif character in ")]":
            depth = max(depth - 1, 0)
        elif depth == 0:
            kept.append(character)
    return "".join(kept)


def blank(text: str) -> str:
    return "".join(char if char == "\n" else " " for char in text)


def mask(text: str) -> Source:
    """Blank comments and the contents of string and character literals, preserving offsets."""
    out: list[str] = []
    literals: dict[int, int] = {}
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            out.append(blank(text[i:end]))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(blank(text[i:end]))
            i = end
        elif text[i] in "\"'":
            quote = '"""' if text.startswith('"""', i) else text[i]
            j = i + len(quote)
            while j < n and not text.startswith(quote, j):
                if text[j] == "\\":
                    j += 1
                elif text[j] == "\n" and len(quote) == 1:
                    break
                j += 1
            j = min(j, n)
            closed = text.startswith(quote, j)
            end = j + len(quote) if closed else j
            if quote == '"' and closed:
                literals[i] = end
            out.append(quote + blank(text[i + len(quote):j]) + (quote if closed else ""))
            i = end
        else:
            out.append(text[i])
            i += 1
    code = "".join(out)
    if len(code) != len(text):
        raise CheckError("internal error: masking changed the text length")
    return Source(text, code, literals)


def matching_paren(code: str, open_index: int) -> int:
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] in "([{":
            depth += 1
        elif code[index] in ")]}":
            depth -= 1
            if depth == 0:
                return index
    return -1


def split_arguments(code: str, open_index: int, close_index: int) -> list[tuple[int, int]]:
    spans, depth, angles, start = [], 0, 0, open_index + 1
    last_close_angle = code.rfind(">", open_index, close_index)
    for index in range(open_index + 1, close_index):
        char = code[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == "<" and depth == 0 and index < last_close_angle:
            before = index - 1
            adjacent = (before >= 0 and not code[before].isspace()
                        and (code[before].isalnum() or code[before] in "_$>.]"))
            after_new = re.search(r"\bnew\s*$", code[open_index:index]) is not None
            if adjacent or after_new:
                # Type arguments after an identifier (`Map<String, Integer>`) or an explicit
                # constructor invocation (`new <String, Integer>Foo()`) nest their commas.
                # Requiring a later `>` keeps `checkArgument(a<b, "msg")` split correctly; an
                # expression with both brackets, such as `a<b, c>d`, is still indistinguishable
                # from a type argument list and merges two arguments.
                angles += 1
        elif char == ">" and angles > 0 and code[index - 1] != "-":
            angles -= 1
        elif char == "," and depth == 0 and angles == 0:
            spans.append((start, index))
            start = index + 1
    spans.append((start, close_index))
    return spans


def skip_space(text: str, index: int, end: int) -> int:
    """The first index at or after index that is not whitespace, never past end."""
    while index < end and text[index].isspace():
        index += 1
    return index


def literal_parts(source: Source, start: int, end: int) -> list[str] | None:
    """The literal contents of an argument made only of `+`-joined string literals."""
    parts: list[str] = []
    index = start
    while index < end:
        index = skip_space(source.code, index, end)
        literal_end = source.literals.get(index)
        if literal_end is None or literal_end > end:
            return None
        parts.append(source.text[index + 1:literal_end - 1])
        index = skip_space(source.code, literal_end, end)
        if index < end:
            if source.code[index] != "+":
                return None
            index += 1
    return parts or None


def starts_with_code_identifier(message: str) -> bool:
    token = LEADING_IDENTIFIER.match(message)
    if not token:
        return False
    return bool(CODE_IDENTIFIER.match(token.group())) or bool(FOLLOWED_BY_QUOTE.match(message, token.end()))


def message_problems(message: str) -> list[str]:
    problems = []
    if message.rstrip().endswith("."):
        problems.append("ends with a period")
    if message[:1].isupper() and not starts_with_code_identifier(message):
        problems.append("starts uppercase without a leading code identifier")
    if BAD_PLACEHOLDER.search(message):
        problems.append("uses a % sequence other than %s, which Guava lenientFormat prints verbatim")
    return problems


def decode_literal(text: str) -> str:
    """Java string-literal contents with the standard escapes decoded."""
    escapes = {"b": "\b", "f": "\f", "n": "\n", "r": "\r", "t": "\t",
               '"': '"', "'": "'", "\\": "\\"}
    out = []
    index = 0
    while index < len(text):
        char = text[index]
        if char == "\\" and index + 1 < len(text):
            escape = text[index + 1]
            if escape == "u" and index + 5 < len(text):
                try:
                    out.append(chr(int(text[index + 2:index + 6], 16)))
                    index += 6
                    continue
                except ValueError:
                    pass
            out.append(escapes.get(escape, escape))
            index += 2
        else:
            out.append(char)
            index += 1
    return "".join(out)


def check_messages(path: str, source: Source) -> Iterator[Violation]:
    for match in MESSAGE_CALL.finditer(source.code):
        open_index = match.end() - 1
        close_index = matching_paren(source.code, open_index)
        if close_index < 0:
            continue
        arguments = split_arguments(source.code, open_index, close_index)
        position = MESSAGE_CALLS[match["name"]]
        if len(arguments) <= position:
            continue
        start, end = arguments[position]
        parts = literal_parts(source, start, end)
        if parts is None:
            continue
        raw = "".join(parts)
        message = decode_literal(raw)
        first = skip_space(source.text, start, end)
        for problem in message_problems(message):
            yield Violation(
                path, source.line_of(first), "R2", f'{match["name"]} message "{raw}" {problem}',
                f'{match["name"]}:{normalize(source.text[start:end])}:{problem}',
            )


def is_reported_accessor(prefix: str, scope: str) -> bool:
    """Whether a declaration prefix names a visible, non-override zero-argument get/is method."""
    plain = top_level(prefix)
    if "=" in plain or not HEAD_LAST_TOKEN.search(prefix) or re.search(r"\b(?:new|return)\b", plain):
        return False
    words = set(re.findall(r"[\w@$]+", prefix))
    visible = bool(words & {"public", "protected"}) or (scope == "interface" and "private" not in words)
    return visible and not OVERRIDE.search(prefix)


def check_accessors(path: str, source: Source) -> Iterator[Violation]:
    """Zero-argument get/is methods declared directly in a type body."""
    code = source.code
    stack: list[str] = []
    segment = 0
    paren_depth = 0
    for index, char in enumerate(code):
        if char == "(":
            paren_depth += 1
            if not stack or stack[-1] == "block":
                continue
            head = METHOD_HEAD.match(code, segment, index)
            if not head or not ACCESSOR_NAME.fullmatch(head["name"]):
                continue
            prefix = head["head"]
            if not is_reported_accessor(prefix, stack[-1]):
                continue
            close = matching_paren(code, index)
            if close < 0 or code[index + 1:close].strip():
                continue
            name_at = head.start("name")
            yield Violation(
                path, source.line_of(name_at), "R1",
                f"accessor {head['name']}() uses the getX()/isX() form; use the bare x() form or add @Override "
                "when it implements a JDK or third-party contract",
                normalize(code[segment:close + 1]),
            )
        elif char == ")":
            paren_depth = max(paren_depth - 1, 0)
        elif char == "{" and paren_depth == 0:
            header = code[segment:index]
            match = TYPE_HEADER.search(header)
            stack.append(("interface" if match["interface"] else "type") if match else "block")
            segment = index + 1
        elif char == "}" and paren_depth == 0:
            if stack:
                stack.pop()
            segment = index + 1
        elif char == ";" and paren_depth == 0:
            segment = index + 1


def override_context(source: Source, offset: int) -> bool:
    """Whether the line at offset, or the annotation lines above it, carry Override.

    The walk reads masked code, so an annotation named in a comment or string
    cannot exempt a line, and it balances brackets so a multiline annotation
    is crossed on the way to the `@Override` above it.
    """
    start = source.text.rfind("\n", 0, offset) + 1
    if OVERRIDE.search(source.code[start:offset]):
        return True
    position = start
    pending = 0
    while position > 0:
        end = position - 1
        while end > 0 and source.code[end].isspace():
            end -= 1
        begin = source.code.rfind("\n", 0, end) + 1
        line = source.code[begin:end + 1]
        pending += sum(line.count(char) for char in ")]}") - sum(line.count(char) for char in "([{")
        pending = max(pending, 0)
        stripped = line.strip()
        if pending == 0:
            if not stripped:
                position = begin
                continue
            if not stripped.startswith("@"):
                return False
            if OVERRIDE.search(line):
                return True
        position = begin
    return False


def is_method_name(code: str, offset: int) -> bool:
    """Whether the token ending at offset is followed by an opening parenthesis."""
    index = offset
    while index < len(code) and code[index] in " \t":
        index += 1
    return index < len(code) and code[index] == "("


def check_spelling(path: str, source: Source) -> Iterator[Violation]:
    for match in SINGLE_L.finditer(source.text):
        if override_context(source, match.start()) and is_method_name(source.code, match.end()):
            continue
        word = match.group().lower()
        yield Violation(
            path, source.line_of(match.start()), "R3",
            f"'{match.group()}' uses the single-l spelling; write '{DOUBLED_L[word]}'",
            f"{word}:{source.line_text(match.start())}",
        )


def check_null_checks(path: str, source: Source) -> Iterator[Violation]:
    for match in REQUIRE_NON_NULL.finditer(source.code):
        yield Violation(
            path, source.line_of(match.start()), "R4",
            "Objects.requireNonNull is not the null-check convention; use Guava checkNotNull",
            source.line_text(match.start()),
        )


CHECKS = (check_accessors, check_messages, check_spelling, check_null_checks)


def java_files(root: Path) -> list[tuple[str, Path]]:
    if not root.is_dir():
        raise CheckError(f"source root {root} is not a directory")
    files = []
    for path in sorted(root.rglob("*.java")):
        relative = path.relative_to(root).as_posix()
        if not relative.startswith(EXCLUDED_PREFIXES):
            files.append((relative, path))
    return files


def scan_text(relative: str, text: str) -> list[Violation]:
    """Every violation in one source text, in rule order."""
    source = mask(text)
    violations = []
    for check in CHECKS:
        violations.extend(check(relative, source))
    return violations


def scan(root: Path) -> list[Violation]:
    violations: list[Violation] = []
    for relative, path in java_files(root):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as error:
            raise CheckError(f"cannot read {path}: {error}") from error
        violations.extend(scan_text(relative, text))
    return violations


def new_violations(base: Iterable[Violation], head: Iterable[Violation]) -> list[Violation]:
    """Head violations whose (path, rule, key) occurs more often than in the base.

    When a key gains occurrences, every head occurrence of it is reported: text alone
    cannot tell which copy is the new one.
    """
    base_counts = collections.Counter((v.path, v.rule, v.key) for v in base)
    head = list(head)
    head_counts = collections.Counter((v.path, v.rule, v.key) for v in head)
    grown = {key for key, count in head_counts.items() if count > base_counts[key]}
    return sorted((v for v in head if (v.path, v.rule, v.key) in grown), key=lambda v: (v.path, v.line, v.rule))


def git(*args: str) -> bytes:
    result = subprocess.run(["git", *args], cwd=ROOT, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise CheckError(f"git {' '.join(args)} failed: {detail}")
    return result.stdout


def default_base() -> str:
    return git("merge-base", DEFAULT_UPSTREAM, "HEAD").decode().strip()


def extract_base(revision: str, target: Path) -> Path:
    commit = git("rev-parse", "--verify", f"{revision}^{{commit}}").decode().strip()
    archive = git("archive", "--format=tar", commit, "--", SOURCE_DIR)
    try:
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(target, filter="data")
    except (tarfile.TarError, OSError) as error:
        raise CheckError(f"cannot extract {SOURCE_DIR} at {commit[:12]}: {error}") from error
    return target / SOURCE_DIR


def revision_argument(value: str) -> str:
    if not value or value.startswith("-"):
        raise argparse.ArgumentTypeError(f"not a revision: {value!r}")
    return value


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    base = parser.add_mutually_exclusive_group()
    base.add_argument("--base", type=revision_argument, help=f"base revision (default: merge base of {DEFAULT_UPSTREAM} and HEAD)")
    base.add_argument("--base-root", type=Path, help="base source root directory, instead of a git revision")
    parser.add_argument("--head-root", type=Path, default=ROOT / SOURCE_DIR, help=f"head source root (default: {SOURCE_DIR})")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        with tempfile.TemporaryDirectory() as scratch:
            if args.base_root is not None:
                base_root = args.base_root
            else:
                base_root = extract_base(args.base or default_base(), Path(scratch))
            base = scan(base_root)
            head = scan(args.head_root)
    except CheckError as error:
        print(f"Java convention check could not run: {error}", file=sys.stderr)
        return 2
    found = new_violations(base, head)
    for violation in found:
        print(f"{SOURCE_DIR}/{violation.path}:{violation.line}: {violation.rule}: {violation.message}")
    if found:
        print(f"{len(found)} new Java convention violation(s); see verification/java-conventions.md", file=sys.stderr)
        return 1
    print("Java conventions: no new violations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
