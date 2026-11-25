#!/usr/bin/env python3
"""Fail when a change weakens existing tests or removes CODEOWNERS protection.

Both the base and head trees are scanned. Existing disable annotations and constant
assertions are grandfathered by baseline subtraction; a head occurrence fails only
when its (path, rule, normalized source) count exceeds the base count. Test cases
are identified by path and name. Exact whole-file moves are aligned by content hash.
An existing test fails when its assertion count decreases.

Protected verification infrastructure changes are printed for CODEOWNERS review but
do not fail by themselves: legitimate maintenance must remain possible. The required
CODEOWNERS entries themselves are mandatory. Narrow reviewed exceptions can be added
to REVIEWED_EXCEPTIONS in this CODEOWNERS-protected script.

The base is a git revision (default: the merge base of origin/dev/v0.5.0 and HEAD),
or a repository directory given by --base-root. The head defaults to the working tree.
Exit status: 0 pass, 1 integrity violations, 2 the check could not run.
"""

from __future__ import annotations

import argparse
import ast
import collections
import dataclasses
import hashlib
import io
import os
import re
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path
from typing import Iterable, Iterator, Sequence

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_UPSTREAM = "origin/dev/v0.5.0"
CODEOWNERS_PATH = ".github/CODEOWNERS"
CODEOWNER = "@monadrome"
CODEOWNERS_MAX_BYTES = 3_000_000
REQUIRED_CODEOWNERS = (
    "/.github/CODEOWNERS",
    "/.github/workflows/",
    "/scripts/check-*.py",
    "/scripts/tests/",
    "/scripts/**/test_*.py",
    "/verification/verification-integrity.md",
)
REVIEWED_EXCEPTIONS: frozenset[tuple[str, str, str]] = frozenset(
    ("scripts/tests/test_verify_run.py", "V2", f"VerifyRunAcceptedExceptionTest.{method}")
    for method in (
        "test_accepts_status_allowed_by_adapter",
        "test_rejects_status_not_allowed_by_adapter",
        "test_rejects_missing_adapter_policy",
        "test_accepts_pass_without_exceptions",
        "test_rejects_missing_final_gate",
        "test_rejects_duplicate_final_gate",
        "test_rejects_failing_final_gate",
        "test_rejects_pass_with_accepted_exceptions_without_exceptions",
        "test_rejects_pass_with_unreported_exceptions",
        "test_rejects_content_after_final_gate",
        "test_rejects_non_pass_required_gate",
        "test_rejects_status_checks_mismatch",
        "test_rejects_v1_schema",
        "test_rejects_non_utf8_report",
        "test_rejects_empty_required_gates_and_checks",
        "test_rejects_adapter_required_gate_mismatch",
    )
)
NON_EXCEPTIBLE_RULES = frozenset({"V6"})

SKIPPED_DIRECTORIES = {
    ".agents",
    ".git",
    ".idea",
    ".pytest_cache",
    ".review-benchmark",
    ".worktrees",
    "__pycache__",
    "node_modules",
    "target",
}


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
class TestCase:
    name: str
    line: int
    assertions: int


@dataclasses.dataclass(frozen=True)
class TestFile:
    digest: str
    tests: tuple[TestCase, ...]
    markers: tuple[Violation, ...]


@dataclasses.dataclass(frozen=True)
class Source:
    text: str
    code: str
    comparison_view: str
    literal_spans: dict[int, int]

    def line_of(self, offset: int) -> int:
        return self.text.count("\n", 0, offset) + 1


def normalize(text: str) -> str:
    return " ".join(text.split())


def blank(text: str) -> str:
    return "".join(char if char == "\n" else " " for char in text)


def javascript_regex_span(text: str, start: int) -> tuple[int, int] | None:
    previous = start - 1
    while previous >= 0 and text[previous].isspace():
        previous -= 1
    if previous >= 0 and text[previous] not in "([{=,:;!&|?+-*%^~<>":
        if re.search(r"\b(?:return|throw|case|delete|void|typeof|instanceof|in|of|yield|await)$", text[:previous + 1]) is None:
            return None
    in_class = False
    index = start + 1
    while index < len(text):
        char = text[index]
        if char == "\\":
            index += 2
            continue
        if char == "\n":
            return None
        if char == "[":
            in_class = True
        elif char == "]":
            in_class = False
        elif char == "/" and not in_class:
            end = index + 1
            while end < len(text) and text[end].isalpha():
                end += 1
            return index, end
        index += 1
    return None


def mask_c_like(text: str, javascript: bool = False) -> Source:
    """Return masked code, a comment-free comparison view, and literal spans."""
    out: list[str] = []
    comparison_view_out: list[str] = []
    literal_spans: dict[int, int] = {}

    def append_both(segment: str) -> None:
        out.append(segment)
        comparison_view_out.append(segment)

    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            end = text.find("\n", i)
            end = n if end < 0 else end
            append_both(blank(text[i:end]))
            i = end
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            end = n if end < 0 else end + 2
            append_both(blank(text[i:end]))
            i = end
        elif javascript and text[i] == "/" and (span := javascript_regex_span(text, i)) is not None:
            close, end = span
            literal_spans[i] = end
            out.append("/" + blank(text[i + 1:close]) + text[close:end])
            comparison_view_out.append(text[i:end])
            i = end
        elif text[i] in "\"'`":
            quote = '"""' if text.startswith('"""', i) else text[i]
            j = i + len(quote)
            while j < n and not text.startswith(quote, j):
                if text[j] == "\\":
                    j += 1
                elif text[j] == "\n" and len(quote) == 1 and quote != "`":
                    break
                j += 1
            j = min(j, n)
            closed = text.startswith(quote, j)
            end = j + len(quote) if closed else j
            literal_spans[i] = end
            out.append(quote + blank(text[i + len(quote):j]) + (quote if closed else ""))
            comparison_view_out.append(text[i:end])
            i = end
        else:
            append_both(text[i])
            i += 1
    code = "".join(out)
    comparison_view = "".join(comparison_view_out)
    if len(code) != len(text) or len(comparison_view) != len(text):
        raise CheckError("internal error: masking changed the text length")
    return Source(text, code, comparison_view, literal_spans)


def matching_delimiter(code: str, open_index: int, opening: str = "(", closing: str = ")") -> int:
    depth = 0
    for index in range(open_index, len(code)):
        if code[index] == opening:
            depth += 1
        elif code[index] == closing:
            depth -= 1
            if depth == 0:
                return index
    return -1


def split_arguments(code: str, open_index: int, close_index: int) -> list[tuple[int, int]]:
    spans, depth, start = [], 0, open_index + 1
    pairs = {"(": ")", "[": "]", "{": "}"}
    closings = set(pairs.values())
    for index in range(open_index + 1, close_index):
        char = code[index]
        if char in pairs:
            depth += 1
        elif char in closings:
            depth -= 1
        elif char == "," and depth == 0:
            spans.append((start, index))
            start = index + 1
    spans.append((start, close_index))
    return spans


def comparison_span(source: Source, start: int, end: int) -> tuple[int, int]:
    while start < end:
        while start < end and source.comparison_view[start].isspace():
            start += 1
        while end > start and source.comparison_view[end - 1].isspace():
            end -= 1
        if start >= end or source.comparison_view[start] != "(" or matching_delimiter(source.code, start) != end - 1:
            break
        start += 1
        end -= 1
    return start, end


def comparison_form(source: Source, start: int, end: int, javascript: bool = False) -> str:
    start, end = comparison_span(source, start, end)
    parts = []
    cursor = start
    for literal_start, literal_end in sorted(source.literal_spans.items()):
        if literal_end <= start:
            continue
        if literal_start >= end:
            break
        parts.append(normalize(source.comparison_view[cursor:literal_start]))
        literal = source.comparison_view[literal_start:literal_end]
        if javascript and literal[:1] in {"'", '"', "`"} and literal[-1:] == literal[:1]:
            literal = literal[1:-1]
        parts.append(literal)
        cursor = literal_end
    parts.append(normalize(source.comparison_view[cursor:end]))
    return "".join(parts)


C_LIKE_SIDE_EFFECT = re.compile(r"\b(?:new|delete|yield|await|function|class)\b|=>|->|\+\+|--|::")
C_LIKE_CALL = re.compile(r"[A-Za-z_$][\w$]*(?:\s*\?\.)?(?:\s*<[^<>()]*>)?\s*\(|[)\]]\s*\(")
C_LIKE_ASSIGNMENT = re.compile(r"(?<![=!<>])(?:\+|-|\*|/|%|&|\||\^|>>>|>>|<<)?=(?!=|>)")
TAGGED_TEMPLATE = re.compile(r"[\w$)\]]\s*`")
TEMPLATE_SUBSTITUTION = re.compile(r"\$\{")
TS_IDENTITY_SUFFIX = re.compile(r"(?!.*(?:\|\||&&))(?:!+|(?:as|satisfies)\s+[\w$.,<>\[\]\s|&-]+)")


def c_like_side_effect(code: str) -> bool:
    without_class_literals = re.sub(r"\.\s*class\b", "", code)
    return bool(
        C_LIKE_SIDE_EFFECT.search(without_class_literals)
        or C_LIKE_CALL.search(code)
        or C_LIKE_ASSIGNMENT.search(code)
    )


def template_side_effect(source: Source, start: int, end: int) -> bool:
    for literal_start, literal_end in source.literal_spans.items():
        if literal_start < start or literal_end > end or source.comparison_view[literal_start] != "`":
            continue
        template = source.comparison_view[literal_start:literal_end]
        for match in TEMPLATE_SUBSTITUTION.finditer(template):
            expression_start = match.end()
            masked = mask_c_like(template[expression_start:-1], javascript=True).code
            close = matching_delimiter("{" + masked, 0, "{", "}")
            if close < 0:
                continue
            expression = template[expression_start:expression_start + close - 1]
            if c_like_side_effect(mask_c_like(expression, javascript=True).code):
                return True
    return False


def typescript_fresh_identity_literal(source: Source, start: int, end: int) -> bool:
    opening = source.comparison_view[start]
    if opening in "{[":
        close = matching_delimiter(source.code, start, opening, "}" if opening == "{" else "]")
        suffix = normalize(source.comparison_view[close + 1:end]) if close >= 0 else ""
        return close >= 0 and (not suffix or TS_IDENTITY_SUFFIX.fullmatch(suffix) is not None)
    if opening == "/" and start in source.literal_spans:
        suffix = normalize(source.comparison_view[source.literal_spans[start]:end])
        return not suffix or TS_IDENTITY_SUFFIX.fullmatch(suffix) is not None
    return False


def self_comparing(
    source: Source,
    left_span: tuple[int, int],
    right_span: tuple[int, int],
    javascript: bool,
    by_identity: bool,
) -> bool:
    if comparison_form(source, *left_span, javascript=javascript) != comparison_form(source, *right_span, javascript=javascript):
        return False
    start, end = comparison_span(source, *left_span)
    if start == end:
        return False
    operand = source.code[start:end]
    if c_like_side_effect(operand):
        return False
    if javascript and (TAGGED_TEMPLATE.search(operand) or template_side_effect(source, start, end)):
        return False
    if by_identity and javascript and typescript_fresh_identity_literal(source, start, end):
        return False
    return True


def call_arguments(source: Source, open_index: int) -> tuple[list[str], list[tuple[int, int]], int]:
    close_index = matching_delimiter(source.code, open_index)
    if close_index < 0:
        return [], [], -1
    arguments = [
        (start, end)
        for start, end in split_arguments(source.code, open_index, close_index)
        if normalize(source.code[start:end])
    ]
    values = [normalize(source.code[start:end]) for start, end in arguments]
    return values, arguments, close_index


def skip_space(code: str, index: int) -> int:
    while index < len(code) and code[index].isspace():
        index += 1
    return index


def violation(source: Source, path: str, start: int, end: int, rule: str, message: str) -> Violation:
    key = normalize(source.text[start:end])
    return Violation(path, source.line_of(start), rule, message, key)


JAVA_TEST = re.compile(
    r"@(?:[A-Za-z_$][\w$]*\.)*(?:Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b"
)
JAVA_ANNOTATION = re.compile(r"@(?:[A-Za-z_$][\w$]*\.)*[A-Za-z_$][\w$]*")
JAVA_DISABLED = re.compile(r"@(?:[A-Za-z_$][\w$]*\.)*(?:Disabled\w*|Ignore)\b")
JAVA_ASSERTION = re.compile(
    r"(?<![\w$])(?P<name>assert[A-Z][\w$]*|fail|verify(?:NoInteractions|NoMoreInteractions)?)\s*\("
)
JAVA_ASSUMPTION = re.compile(r"(?<![\w$])(?P<name>assumeTrue|assumeFalse)\s*\(")
JAVA_NAME_BEFORE_PARAMETERS = re.compile(r"(?P<name>[A-Za-z_$][\w$]*)\s*$")


def annotation_end(code: str, end: int) -> int:
    position = skip_space(code, end)
    if position < len(code) and code[position] == "(":
        close = matching_delimiter(code, position)
        return len(code) if close < 0 else close + 1
    return end


def java_test_at(source: Source, path: str, match: re.Match[str]) -> TestCase | None:
    code = source.code
    location = f"{path}:{source.line_of(match.start())}"
    position = annotation_end(code, match.end())
    while True:
        position = skip_space(code, position)
        if position >= len(code) or code[position] != "@":
            break
        annotation = JAVA_ANNOTATION.match(code, position)
        if annotation is None:
            return None
        position = annotation_end(code, annotation.end())
    open_parameters = code.find("(", position)
    earlier_stop = min((index for index in (code.find("{", position), code.find(";", position)) if index >= 0), default=-1)
    if earlier_stop >= 0 and (open_parameters < 0 or earlier_stop < open_parameters):
        return None
    if open_parameters < 0:
        raise CheckError(f"cannot parse Java test method at {location}")
    name = JAVA_NAME_BEFORE_PARAMETERS.search(code, position, open_parameters)
    if name is None:
        raise CheckError(f"cannot parse Java test method at {location}")
    close_parameters = matching_delimiter(code, open_parameters)
    if close_parameters < 0:
        raise CheckError(f"cannot parse Java test method at {location}")
    open_body = code.find("{", close_parameters + 1)
    semicolon = code.find(";", close_parameters + 1)
    if open_body < 0 or (0 <= semicolon < open_body):
        raise CheckError(f"cannot parse Java test method at {location}")
    close_body = matching_delimiter(code, open_body, "{", "}")
    if close_body < 0:
        raise CheckError(f"cannot parse Java test method at {location}")
    assertions = sum(1 for _ in JAVA_ASSERTION.finditer(code, open_body + 1, close_body))
    return TestCase(name["name"], source.line_of(name.start("name")), assertions)


def java_noops(source: Source, path: str) -> Iterator[Violation]:
    code = source.code
    for match in JAVA_ASSERTION.finditer(code):
        name = match["name"]
        if name not in {"assertTrue", "assertFalse", "assertEquals", "assertSame", "assertThat"}:
            continue
        open_index = match.end() - 1
        values, arguments, close_index = call_arguments(source, open_index)
        if close_index < 0:
            continue
        noop = False
        end = close_index + 1
        if name == "assertTrue" and values and (values[0] == "true" or values[-1] == "true"):
            noop = True
        elif name == "assertFalse" and values and (values[0] == "false" or values[-1] == "false"):
            noop = True
        elif name in {"assertEquals", "assertSame"} and len(arguments) >= 2 and self_comparing(source, arguments[0], arguments[1], False, False):
            noop = True
        elif name == "assertThat" and values in (["true"], ["false"]):
            chain = re.match(r"\s*\.\s*(?P<method>isTrue|isFalse)\s*\(\s*\)", code[end:])
            if chain and ((values[0] == "true") == (chain["method"] == "isTrue")):
                noop = True
                end += chain.end()
        if noop:
            yield violation(source, path, match.start(), end, "V5", f"new constant or self-comparing {name} assertion")


def java_assumptions(source: Source, path: str) -> Iterator[Violation]:
    for match in JAVA_ASSUMPTION.finditer(source.code):
        values, _, close_index = call_arguments(source, match.end() - 1)
        if close_index < 0:
            continue
        rejected = {"false", "Boolean.FALSE"} if match["name"] == "assumeTrue" else {"true", "Boolean.TRUE"}
        if values and (values[0] in rejected or values[-1] in rejected):
            yield violation(source, path, match.start(), close_index + 1, "V3", "new constant-failing test assumption")


def scan_java(path: str, text: str, digest: str) -> TestFile:
    source = mask_c_like(text)
    tests = [test for match in JAVA_TEST.finditer(source.code) if (test := java_test_at(source, path, match)) is not None]
    markers = list(java_noops(source, path))
    markers.extend(java_assumptions(source, path))
    for match in JAVA_DISABLED.finditer(source.code):
        end = annotation_end(source.code, match.end())
        markers.append(violation(source, path, match.start(), end, "V3", "new @Disabled or @Ignore test annotation"))
    return TestFile(digest, tuple(tests), tuple(markers))


def dotted_name(node: ast.AST) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        prefix = dotted_name(node.value)
        return f"{prefix}.{node.attr}" if prefix else node.attr
    return ""


def is_pytest_module(module: str | None) -> bool:
    return module is not None and (module == "pytest" or module.startswith("pytest."))


def python_pytest_imports(tree: ast.AST) -> tuple[dict[str, str], list[str]]:
    aliases: dict[str, str] = {}
    wildcard_imports: list[str] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if alias.asname and is_pytest_module(alias.name):
                    aliases[alias.asname] = alias.name
        elif isinstance(node, ast.ImportFrom) and is_pytest_module(node.module):
            for alias in node.names:
                if alias.name == "*":
                    wildcard_imports.append(node.module)
                else:
                    aliases[alias.asname or alias.name] = f"{node.module}.{alias.name}"
    return aliases, wildcard_imports


def python_resolved_name(node: ast.AST, aliases: dict[str, str], wildcard_imports: list[str]) -> str:
    name = dotted_name(node)
    head, separator, tail = name.partition(".")
    if head not in aliases and head in PYTEST_WILDCARD_NAMES and wildcard_imports:
        candidates = [f"{module}.{name}" for module in wildcard_imports]
        return next((candidate for candidate in candidates if candidate in PYTHON_RUNTIME_DISABLED), candidates[0])
    return aliases.get(head, head) + separator + tail


def python_function_bindings(function: ast.FunctionDef | ast.AsyncFunctionDef) -> set[str]:
    arguments = function.args
    names = {
        argument.arg
        for argument in (*arguments.posonlyargs, *arguments.args, *arguments.kwonlyargs)
    }
    names.update(argument.arg for argument in (arguments.vararg, arguments.kwarg) if argument is not None)

    def collect(node: ast.AST) -> None:
        if isinstance(node, (ast.ListComp, ast.SetComp, ast.DictComp, ast.GeneratorExp)):
            for generator in node.generators:
                collect(generator.iter)
                for condition in generator.ifs:
                    collect(condition)
            if isinstance(node, ast.DictComp):
                collect(node.key)
                collect(node.value)
            else:
                collect(node.elt)
            return
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
                names.add(child.name)
                continue
            if isinstance(child, ast.Name) and isinstance(child.ctx, ast.Store):
                names.add(child.id)
            elif isinstance(child, (ast.Import, ast.ImportFrom)):
                for alias in child.names:
                    if alias.name == "*":
                        continue
                    module = child.module if isinstance(child, ast.ImportFrom) else alias.name
                    binding = alias.asname or alias.name.split(".")[0]
                    if is_pytest_module(module):
                        names.discard(binding)
                    else:
                        names.add(binding)
            elif isinstance(child, ast.ExceptHandler) and child.name:
                names.add(child.name)
            collect(child)

    collect(function)
    return names


def python_shadowed_calls(tree: ast.AST, aliases: dict[str, str]) -> set[int]:
    shadowed: set[int] = set()

    def visit(node: ast.AST, scopes: list[set[str]]) -> None:
        decorators = node.decorator_list if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)) else []
        for decorator in decorators:
            visit(decorator, scopes)
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            scopes = [*scopes, python_function_bindings(node)]
        if isinstance(node, (ast.Call, ast.Name, ast.Attribute)):
            head = dotted_name(node.func if isinstance(node, ast.Call) else node).partition(".")[0]
            if head in aliases or head in PYTEST_WILDCARD_NAMES or head == "pytest":
                if any(head in scope for scope in scopes):
                    shadowed.add(id(node))
        for child in ast.iter_child_nodes(node):
            if child not in decorators:
                visit(child, scopes)

    visit(tree, [])
    return shadowed


PYTHON_DISABLED = {"skip", "skipif", "skipunless", "expectedfailure", "xfail"}
PYTEST_WILDCARD_NAMES = PYTHON_DISABLED | {"importorskip", "mark"}
PYTHON_RUNTIME_DISABLED = {
    "pytest.skip",
    "pytest.xfail",
    "pytest.importorskip",
    "pytest.mark.skip",
    "pytest.mark.skipif",
    "pytest.mark.xfail",
}


def python_assertion_call(node: ast.AST) -> bool:
    if not isinstance(node, ast.Call):
        return False
    name = dotted_name(node.func)
    final = name.rsplit(".", 1)[-1]
    return final.startswith("assert") or (name.startswith("pytest.") and final in {"raises", "warns"})


def python_noops(tree: ast.AST, path: str, text: str) -> Iterator[Violation]:
    for node in ast.walk(tree):
        noop = False
        name = "assert"
        if isinstance(node, ast.Assert):
            if isinstance(node.test, ast.Constant) and node.test.value is True:
                noop = True
            elif isinstance(node.test, ast.Compare) and len(node.test.ops) == 1 and isinstance(node.test.ops[0], ast.Eq):
                noop = len(node.test.comparators) == 1 and ast.dump(node.test.left) == ast.dump(node.test.comparators[0])
        elif isinstance(node, ast.Call):
            name = dotted_name(node.func).rsplit(".", 1)[-1]
            if name in {"assertTrue", "assertFalse"} and node.args:
                noop = isinstance(node.args[0], ast.Constant) and node.args[0].value is (name == "assertTrue")
            elif name in {"assertEqual", "assertIs"} and len(node.args) >= 2:
                noop = ast.dump(node.args[0]) == ast.dump(node.args[1])
            elif name == "assertIsNone" and len(node.args) == 1:
                noop = isinstance(node.args[0], ast.Constant) and node.args[0].value is None
        if noop:
            segment = ast.get_source_segment(text, node) or name
            yield Violation(path, node.lineno, "V5", f"new constant or self-comparing {name} assertion", normalize(segment))


def scan_python(path: str, text: str, digest: str) -> TestFile:
    try:
        tree = ast.parse(text, filename=path)
    except SyntaxError as error:
        raise CheckError(f"cannot parse {path}: {error}") from error
    tests: list[TestCase] = []

    def visit(node: ast.AST, prefix: str = "") -> None:
        for child in ast.iter_child_nodes(node):
            if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)):
                qualified = prefix + child.name
                if child.name.startswith("test"):
                    assertions = sum(isinstance(item, ast.Assert) or python_assertion_call(item) for item in ast.walk(child))
                    tests.append(TestCase(qualified, child.lineno, assertions))
                visit(child, qualified + ".")
            elif isinstance(child, ast.ClassDef):
                visit(child, prefix + child.name + ".")
            else:
                visit(child, prefix)

    visit(tree)
    aliases, wildcard_imports = python_pytest_imports(tree)
    shadowed_calls = python_shadowed_calls(tree, aliases)
    markers = list(python_noops(tree, path, text))
    decorator_calls: set[int] = set()
    for node in ast.walk(tree):
        if not isinstance(node, (ast.ClassDef, ast.FunctionDef, ast.AsyncFunctionDef)):
            continue
        for decorator in node.decorator_list:
            if isinstance(decorator, ast.Call):
                decorator_calls.add(id(decorator))
            target = decorator.func if isinstance(decorator, ast.Call) else decorator
            if id(decorator) in shadowed_calls or id(target) in shadowed_calls:
                continue
            name = python_resolved_name(target, aliases, wildcard_imports)
            if name.rsplit(".", 1)[-1].lower() in PYTHON_DISABLED:
                segment = ast.get_source_segment(text, decorator) or name
                markers.append(Violation(path, decorator.lineno, "V3", "new skip, expected-failure, or xfail decorator", normalize(segment)))
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call) or id(node) in decorator_calls or id(node) in shadowed_calls:
            continue
        name = python_resolved_name(node.func, aliases, wildcard_imports)
        if name.rsplit(".", 1)[-1].lower() == "skiptest" or name in PYTHON_RUNTIME_DISABLED:
            segment = ast.get_source_segment(text, node) or name
            markers.append(Violation(path, node.lineno, "V3", "new runtime test skip or xfail", normalize(segment)))
    return TestFile(digest, tuple(tests), tuple(markers))


TS_TEST = re.compile(
    r"(?<![\w$])(?:test|it)(?:\s*\.\s*(?:skip|only|todo))?\s*\(\s*(?P<quote>['\"`])"
)
TS_SELECTION = re.compile(r"(?<![\w$])(?:test|it|describe)\s*\.\s*(?:skip|only|todo)\b|(?<![\w$])x(?:test|it|describe)\b")
TS_RUNTIME_SELECTION = re.compile(r"(?<![\w$])(?:t|context)\s*\.\s*(?:skip|only|todo)\s*\(")
TS_OPTIONS_SELECTION = re.compile(r"(?<![\w$])(?:skip|todo|only)\s*:\s*(?!false\b)(?:true\b|['\"`]|[A-Za-z_$0-9])")
TS_ASSERTION = re.compile(r"(?<![\w$])(?:assert(?:\s*\.\s*[A-Za-z_$][\w$]*)?|expect)\s*\(")
TS_ASSERT_CALL = re.compile(
    r"(?<![\w$])assert(?:\s*\.\s*(?P<method>ok|equal|strictEqual|deepEqual|deepStrictEqual))?\s*\("
)
TS_EXPECT_CALL = re.compile(r"(?<![\w$])expect\s*\(")
TS_EXPECT_EQUALITY = re.compile(r"\s*\.\s*(?P<method>toBe|toEqual|toStrictEqual)\s*\(")


def scan_typescript(path: str, text: str, digest: str) -> TestFile:
    source = mask_c_like(text, javascript=True)
    code = source.code
    tests: list[TestCase] = []
    markers: list[Violation] = []
    for match in TS_TEST.finditer(code):
        quote_at = match.end() - 1
        quote = match["quote"]
        close_quote = code.find(quote, quote_at + 1)
        open_call = code.rfind("(", match.start(), quote_at)
        close_call = matching_delimiter(code, open_call) if open_call >= 0 else -1
        if close_quote < 0 or close_call < 0:
            raise CheckError(f"cannot parse TypeScript test at {path}:{source.line_of(match.start())}")
        title = normalize(text[quote_at + 1:close_quote])
        assertions = sum(1 for _ in TS_ASSERTION.finditer(code, open_call + 1, close_call))
        tests.append(TestCase(title, source.line_of(match.start()), assertions))
        arrow = code.find("=>", open_call + 1, close_call)
        function = code.find("function", open_call + 1, close_call)
        callback = min((index for index in (arrow, function) if index >= 0), default=close_call)
        for option in TS_OPTIONS_SELECTION.finditer(code, open_call + 1, callback):
            markers.append(violation(source, path, option.start(), option.end(), "V3", "new test options suppression"))
    markers.extend(
        violation(source, path, match.start(), match.end(), "V3", "new skipped, todo, exclusive, or x-prefixed test selection")
        for match in TS_SELECTION.finditer(code)
    )
    markers.extend(
        violation(source, path, match.start(), match.end(), "V3", "new runtime test skip, todo, or exclusive selection")
        for match in TS_RUNTIME_SELECTION.finditer(code)
    )
    for match in TS_ASSERT_CALL.finditer(code):
        open_index = match.end() - 1
        values, arguments, close_index = call_arguments(source, open_index)
        if close_index < 0:
            continue
        method = match["method"]
        noop = (method in {None, "ok"} and values[:1] == ["true"]) or (
            method in {"equal", "strictEqual", "deepEqual", "deepStrictEqual"}
            and len(arguments) >= 2
            and self_comparing(source, arguments[0], arguments[1], True, method in {"equal", "strictEqual"})
        )
        if noop:
            markers.append(violation(source, path, match.start(), close_index + 1, "V5", "new constant or self-comparing assertion"))
    for match in TS_EXPECT_CALL.finditer(code):
        _, received, close_index = call_arguments(source, match.end() - 1)
        if close_index < 0 or len(received) != 1:
            continue
        chain = TS_EXPECT_EQUALITY.match(code, close_index + 1)
        if chain is None:
            continue
        _, expected, expectation_close = call_arguments(source, chain.end() - 1)
        if expectation_close >= 0 and len(expected) == 1 and self_comparing(source, received[0], expected[0], True, chain["method"] == "toBe"):
            markers.append(violation(source, path, match.start(), expectation_close + 1, "V5", "new constant or self-comparing expect assertion"))
    return TestFile(digest, tuple(tests), tuple(markers))


def repository_files(root: Path) -> Iterator[tuple[str, Path]]:
    if not root.is_dir():
        raise CheckError(f"repository root {root} is not a directory")
    for current, directories, files in os.walk(root):
        directories[:] = sorted(name for name in directories if name not in SKIPPED_DIRECTORIES)
        for name in sorted(files):
            path = Path(current) / name
            yield path.relative_to(root).as_posix(), path


def is_test_file(relative: str) -> bool:
    path = Path(relative)
    parts = path.parts
    if path.suffix == ".java" and any(parts[index:index + 2] == ("src", "test") for index in range(len(parts) - 1)):
        return True
    if parts[:1] == ("scripts",) and path.name.startswith("test_") and path.suffix == ".py":
        return True
    return parts[:3] == ("tools", "llm-review-bench", "test") and path.name.endswith(".test.ts")


def is_protected_file(relative: str) -> bool:
    path = Path(relative)
    parts = path.parts
    return (
        relative in {CODEOWNERS_PATH, "verification/verification-integrity.md"}
        or parts[:2] == (".github", "workflows")
        or (len(parts) == 2 and parts[0] == "scripts" and path.name.startswith("check-") and path.suffix == ".py")
        or parts[:2] == ("scripts", "tests")
        or (parts[:1] == ("scripts",) and path.name.startswith("test_") and path.suffix == ".py")
    )


def scan_tests(root: Path) -> dict[str, TestFile]:
    files: dict[str, TestFile] = {}
    for relative, path in repository_files(root):
        if not is_test_file(relative):
            continue
        try:
            raw = path.read_bytes()
            text = raw.decode("utf-8")
        except (OSError, UnicodeDecodeError) as error:
            raise CheckError(f"cannot read {path}: {error}") from error
        digest = hashlib.sha256(raw).hexdigest()
        if path.suffix == ".java":
            files[relative] = scan_java(relative, text, digest)
        elif path.suffix == ".py":
            files[relative] = scan_python(relative, text, digest)
        else:
            files[relative] = scan_typescript(relative, text, digest)
    return files


def exact_moves(base: dict[str, TestFile], head: dict[str, TestFile]) -> dict[str, str]:
    base_only = set(base) - set(head)
    head_only = set(head) - set(base)
    base_hashes: dict[str, list[str]] = collections.defaultdict(list)
    head_hashes: dict[str, list[str]] = collections.defaultdict(list)
    for path in sorted(base_only):
        base_hashes[base[path].digest].append(path)
    for path in sorted(head_only):
        head_hashes[head[path].digest].append(path)
    return {
        base_paths[0]: head_hashes[digest][0]
        for digest, base_paths in base_hashes.items()
        if len(base_paths) == 1 and len(head_hashes.get(digest, [])) == 1
    }


def align_base_files(base: dict[str, TestFile], moves: dict[str, str]) -> dict[str, TestFile]:
    aligned = {}
    for path, file in base.items():
        target = moves.get(path, path)
        markers = tuple(dataclasses.replace(marker, path=target) for marker in file.markers)
        aligned[target] = dataclasses.replace(file, markers=markers)
    return aligned


def grouped_tests(file: TestFile) -> dict[str, list[TestCase]]:
    grouped: dict[str, list[TestCase]] = collections.defaultdict(list)
    for test in file.tests:
        grouped[test.name].append(test)
    return grouped


def compare_tests(base: dict[str, TestFile], head: dict[str, TestFile]) -> tuple[list[Violation], dict[str, TestFile]]:
    moves = exact_moves(base, head)
    aligned = align_base_files(base, moves)
    found: list[Violation] = []
    for path, file in base.items():
        if path not in head and path not in moves and file.tests:
            found.append(Violation(path, 0, "V1", f"test file with {len(file.tests)} test case(s) was deleted", path))
    for path, base_file in aligned.items():
        head_file = head.get(path)
        if head_file is None:
            continue
        base_tests = grouped_tests(base_file)
        head_tests = grouped_tests(head_file)
        for name, base_cases in base_tests.items():
            head_cases = head_tests.get(name, [])
            if len(head_cases) < len(base_cases):
                found.append(Violation(
                    path,
                    base_cases[0].line,
                    "V2",
                    f"test case {name!r} was removed or renamed ({len(base_cases)} -> {len(head_cases)})",
                    name,
                ))
            for base_case, head_case in zip(base_cases, head_cases):
                if head_case.assertions < base_case.assertions:
                    found.append(Violation(
                        path,
                        head_case.line,
                        "V4",
                        f"test case {name!r} assertion count decreased ({base_case.assertions} -> {head_case.assertions})",
                        name,
                    ))
    return found, aligned


def new_markers(base: Iterable[Violation], head: Iterable[Violation]) -> list[Violation]:
    base_counts = collections.Counter((item.path, item.rule, item.key) for item in base)
    head = list(head)
    head_counts = collections.Counter((item.path, item.rule, item.key) for item in head)
    grown = {key for key, count in head_counts.items() if count > base_counts[key]}
    return [item for item in head if (item.path, item.rule, item.key) in grown]


def protected_files(root: Path) -> dict[str, bytes]:
    found = {}
    for relative, path in repository_files(root):
        if not is_protected_file(relative):
            continue
        try:
            found[relative] = path.read_bytes()
        except OSError as error:
            raise CheckError(f"cannot read {path}: {error}") from error
    return found


def protected_changes(base: dict[str, bytes], head: dict[str, bytes]) -> list[str]:
    changes = []
    for path in sorted(set(base) | set(head)):
        if path not in base:
            changes.append(f"PROTECTED {path}: added; CODEOWNERS review required")
        elif path not in head:
            changes.append(f"PROTECTED {path}: deleted; CODEOWNERS review required")
        elif base[path] != head[path]:
            changes.append(f"PROTECTED {path}: modified; CODEOWNERS review required")
    return changes


def codeowner_regex(pattern: str) -> re.Pattern[str] | None:
    if pattern.startswith("!") or "[" in pattern or "]" in pattern:
        return None
    anchored = pattern.startswith("/")
    body = pattern[1:] if anchored else pattern
    directory = body.endswith("/")
    body = body[:-1] if directory else body
    if not body:
        return None
    prefix = "" if anchored or "/" in body else "(?:.*/)?"
    last_segment = body.rsplit("/", 1)[-1]
    translated: list[str] = []
    index = 0
    while index < len(body):
        char = body[index]
        if char == "*" and index + 1 < len(body) and body[index + 1] == "*":
            if (index == 0 or body[index - 1] == "/") and index + 2 < len(body) and body[index + 2] == "/":
                translated.append("(?:.*/)?")
                index += 3
            else:
                translated.append(".*")
                index += 2
        elif char == "*":
            translated.append("[^/]*")
            index += 1
        elif char == "?":
            translated.append("[^/]")
            index += 1
        elif char == "\\" and index + 1 < len(body):
            translated.append(re.escape(body[index + 1]))
            index += 2
        else:
            translated.append(re.escape(char))
            index += 1
    if directory:
        suffix = "/.*"
    elif "*" in last_segment or "?" in last_segment:
        suffix = ""
    else:
        suffix = "(?:/.*)?"
    return re.compile("^" + prefix + "".join(translated) + suffix + "$")


def parse_codeowner_entries(text: str) -> list[tuple[str, re.Pattern[str], list[str]]]:
    entries: list[tuple[str, re.Pattern[str], list[str]]] = []
    for line in text.splitlines():
        content = line.split("#", 1)[0].strip()
        if not content:
            continue
        fields = content.split()
        pattern = fields[0]
        compiled = codeowner_regex(pattern)
        if compiled is not None:
            entries.append((pattern, compiled, fields[1:]))
    return entries


def last_match_retains_owner(matching: list[tuple[str, re.Pattern[str], list[str]]]) -> bool:
    return bool(matching and CODEOWNER in matching[-1][2])


def codeowner_violations(root: Path) -> list[Violation]:
    path = root / CODEOWNERS_PATH
    try:
        raw = path.read_bytes()
    except OSError as error:
        return [Violation(CODEOWNERS_PATH, 0, "V6", f"cannot read required CODEOWNERS: {error}", CODEOWNERS_PATH)]
    if len(raw) >= CODEOWNERS_MAX_BYTES:
        return [Violation(CODEOWNERS_PATH, 0, "V6", "CODEOWNERS must stay below GitHub's 3 MB loading limit", "size")]
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as error:
        return [Violation(CODEOWNERS_PATH, 0, "V6", f"cannot read required CODEOWNERS: {error}", CODEOWNERS_PATH)]
    entries = parse_codeowner_entries(text)
    found = []
    for required in REQUIRED_CODEOWNERS:
        matching = [entry for entry in entries if entry[0] == required]
        if not last_match_retains_owner(matching):
            found.append(Violation(
                CODEOWNERS_PATH, 0, "V6", f"CODEOWNERS entry {required!r} must retain {CODEOWNER}", required
            ))
    for protected in sorted(protected_files(root)):
        matching = [entry for entry in entries if entry[1].fullmatch(protected)]
        if not last_match_retains_owner(matching):
            found.append(Violation(
                CODEOWNERS_PATH,
                0,
                "V6",
                f"effective CODEOWNERS coverage for {protected!r} must retain {CODEOWNER}",
                f"coverage:{protected}",
            ))
    return found


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
    archive = git("archive", "--format=tar", commit)
    try:
        with tarfile.open(fileobj=io.BytesIO(archive)) as tar:
            tar.extractall(target, filter="data")
    except (tarfile.TarError, OSError) as error:
        raise CheckError(f"cannot extract repository at {commit[:12]}: {error}") from error
    return target


def revision_argument(value: str) -> str:
    if not value or value.startswith("-"):
        raise argparse.ArgumentTypeError(f"not a revision: {value!r}")
    return value


def parse_args(argv: Sequence[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    base = parser.add_mutually_exclusive_group()
    base.add_argument("--base", type=revision_argument, help=f"base revision (default: merge base of {DEFAULT_UPSTREAM} and HEAD)")
    base.add_argument("--base-root", type=Path, help="base repository directory, instead of a git revision")
    parser.add_argument("--head-root", type=Path, default=ROOT, help="head repository directory")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        with tempfile.TemporaryDirectory() as scratch:
            base_root = args.base_root or extract_base(args.base or default_base(), Path(scratch))
            base_files = scan_tests(base_root)
            head_files = scan_tests(args.head_root)
            found, aligned_base = compare_tests(base_files, head_files)
            base_markers = (marker for file in aligned_base.values() for marker in file.markers)
            head_markers = (marker for file in head_files.values() for marker in file.markers)
            found.extend(new_markers(base_markers, head_markers))
            found.extend(codeowner_violations(args.head_root))
            changes = protected_changes(protected_files(base_root), protected_files(args.head_root))
    except CheckError as error:
        print(f"Verification integrity check could not run: {error}", file=sys.stderr)
        return 2
    found = sorted(
        (
            item
            for item in found
            if item.rule in NON_EXCEPTIBLE_RULES or (item.path, item.rule, item.key) not in REVIEWED_EXCEPTIONS
        ),
        key=lambda item: (item.path, item.line, item.rule, item.key),
    )
    for item in found:
        location = f"{item.path}:{item.line}" if item.line else item.path
        print(f"{location}: {item.rule}: {item.message}")
    for change in changes:
        print(change)
    if found:
        print(f"{len(found)} verification integrity violation(s); see verification/verification-integrity.md", file=sys.stderr)
        return 1
    print(f"Verification integrity: no new violations; {len(changes)} protected change(s) reported")
    return 0


if __name__ == "__main__":
    sys.exit(main())
