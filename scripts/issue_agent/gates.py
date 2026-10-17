from __future__ import annotations

import dataclasses
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path
from typing import List, Optional, Sequence, Tuple

from . import policy
from .proc import git, run, run_logged

TEST_PATH_RE = re.compile(r"^src/test/java/(?P<cls>.+(Test|Tests))\.java$")
MAIN_PATH_RE = re.compile(r"^src/main/java/(?P<cls>.+)\.java$")
COMPILE_ERROR_RE = re.compile(r"COMPILATION ERROR|Compilation failure", re.I)
TEST_FAILURE_RE = re.compile(r"Tests run: \d+, Failures: (?:[1-9]\d*, Errors: \d+|\d+, Errors: [1-9])")
JAVAC_RE = re.compile(r"Compiling \d+ source files? .*target[/\\]classes")
BUILD_INPUTS = ("src/", "demo/", "verification/", ".mvn/", "pom.xml")
PIT_REPORT = Path("target/pit-reports/mutations.xml")
REPO_CHECKS = ("scripts/check-english-docs.py", "scripts/check-local-links.py", "scripts/check-design-routes.py")
TERMINAL_GATES = frozenset({"diff-policy", "secret-scan"})


@dataclasses.dataclass
class Gate:
    name: str
    status: str
    detail: str = ""


def changes(clone: Path, base_sha: str) -> List[Tuple[str, str]]:
    listing = git(clone, "diff", "--name-status", "-M", f"{base_sha}..HEAD")
    entries = []
    for line in listing.splitlines():
        parts = line.split("\t")
        entries += [(parts[0], path) for path in parts[1:]]
    return entries


def export_head(clone: Path, workdir: Path) -> Path:
    if workdir.exists():
        shutil.rmtree(workdir)
    workdir.mkdir(parents=True)
    archive = workdir.with_suffix(".tar")
    run(["git", "archive", "--format=tar", "-o", str(archive), "HEAD"], cwd=clone)
    run(["tar", "-xf", str(archive), "-C", str(workdir)])
    archive.unlink()
    return workdir


def maven_env(java_home: str) -> dict:
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = f"{java_home}/bin{os.pathsep}{env.get('PATH', '')}"
    return env


def mvn(workdir: Path, log: Path, java_home: str, timeout_min: int, *goals: str) -> int:
    return run_logged(["mvn", "-B", "-ntp", "-o", *goals], log, cwd=workdir, env=maven_env(java_home), timeout=timeout_min * 60)


def fresh_verify(clone: Path, workdir: Path, logs: Path, java_home: str, timeout_min: int) -> List[Gate]:
    export_head(clone, workdir)
    if mvn(workdir, logs / "spotless.log", java_home, timeout_min, "spotless:check") != 0:
        return [Gate("spotless", "fail", "mvn spotless:check failed on a clean export; run mvn -o spotless:apply and commit")]
    log = logs / "verify.log"
    if mvn(workdir, log, java_home, timeout_min, "clean", "verify") != 0:
        return [Gate("spotless", "pass"), Gate("verify", "fail", "mvn clean verify failed on a clean export:\n" + log.read_text(errors="replace")[-3000:])]
    compiled = JAVAC_RE.search(log.read_text(errors="replace"))
    verdict = Gate("verify", "pass") if compiled else Gate("verify", "inconclusive", "javac did not compile main sources")
    return [Gate("spotless", "pass"), verdict]


def repo_checks(clone: Path, logs: Path) -> Gate:
    failures = []
    for script in REPO_CHECKS:
        if (clone / script).exists() and run_logged([sys.executable, script], logs / (Path(script).stem + ".log"), cwd=clone) != 0:
            failures.append(script)
    if (clone / "scripts").is_dir():
        args = [sys.executable, "-m", "unittest", "discover", "-s", "scripts", "-t", "scripts"]
        if run_logged(args, logs / "script-tests.log", cwd=clone) != 0:
            failures.append("script tests")
    return Gate("repo-checks", "fail", "failed: " + ", ".join(failures)) if failures else Gate("repo-checks", "pass")


def secret_scan(clone: Path, base_sha: str) -> Gate:
    current, flagged = "", set()
    for line in git(clone, "diff", "--unified=0", "--no-color", f"{base_sha}..HEAD").splitlines():
        if line.startswith("+++ "):
            current = line[6:] if line.startswith("+++ b/") else line[4:]
        elif line.startswith("+") and policy.SECRET_RE.search(line):
            flagged.add(current)
    if flagged:
        return Gate("secret-scan", "fail", "possible credentials in added lines of " + ", ".join(sorted(flagged)))
    return Gate("secret-scan", "pass")


def _blob(clone: Path, sha: str, path: str) -> Optional[bytes]:
    result = subprocess.run(["git", "show", f"{sha}:{path}"], cwd=clone, capture_output=True)
    return result.stdout if result.returncode == 0 else None


def reverse_verify(clone: Path, base_sha: str, entries: Sequence[Tuple[str, str]], workdir: Path, logs: Path, java_home: str, timeout_min: int) -> Gate:
    tests = sorted({m.group("cls").replace("/", ".") for _, p in entries for m in [TEST_PATH_RE.match(p)] if m})
    mains = sorted({p for _, p in entries if MAIN_PATH_RE.match(p)})
    if not mains:
        return Gate("reverse-verify", "skip", "no main sources changed")
    if not tests:
        return Gate("reverse-verify", "inconclusive", "a bug fix changed main sources without a changed *Test class")
    export_head(clone, workdir)
    tests = [t for t in tests if (workdir / "src/test/java" / (t.replace(".", "/") + ".java")).exists()]
    for path in mains:
        target, original = workdir / path, _blob(clone, base_sha, path)
        if original is None:
            target.unlink(missing_ok=True)
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(original)
    log = logs / "reverse-verify.log"
    selection = "-Dtest=" + ",".join(tests)
    code = mvn(workdir, log, java_home, timeout_min, "test", selection, "-Dsurefire.failIfNoSpecifiedTests=false")
    output = log.read_text(errors="replace")
    return classify_reverse(code, output, tests)


def classify_reverse(code: int, output: str, tests: Sequence[str]) -> Gate:
    names = ", ".join(tests)
    if code == 0:
        return Gate("reverse-verify", "fail", f"{names} pass with the main-source fix reverted; the regression test does not pin the defect")
    if TEST_FAILURE_RE.search(output) and not COMPILE_ERROR_RE.search(output):
        return Gate("reverse-verify", "pass", f"{names} fail without the fix")
    return Gate("reverse-verify", "inconclusive", "the build failed without reaching the new tests (likely they need the new API)")


def pit_gate(clone: Path, entries: Sequence[Tuple[str, str]]) -> Tuple[Gate, dict]:
    mains = [clone / p for s, p in entries if MAIN_PATH_RE.match(p) and not s.startswith("D")]
    if not mains:
        return Gate("pit", "skip", "no main sources changed"), {}
    report = clone / PIT_REPORT
    if not report.exists():
        return Gate("pit", "fail", "no target/pit-reports/mutations.xml; run PIT on the touched classes as AGENTS.md describes"), {}
    newest_edit = max(p.stat().st_mtime for p in mains if p.exists())
    if report.stat().st_mtime < newest_edit:
        return Gate("pit", "fail", "the PIT report predates the last main-source edit; re-run PIT"), {}
    summary = parse_pit(report)
    return Gate("pit", "pass", f"{summary['killed']} killed, {len(summary['survivors'])} surviving"), summary


def parse_pit(report: Path) -> dict:
    killed, survivors = 0, []
    for mutation in ElementTree.parse(report).getroot().iter("mutation"):
        status = mutation.get("status", "")
        if status in ("KILLED", "TIMED_OUT", "MEMORY_ERROR"):
            killed += 1
        elif status in ("SURVIVED", "NO_COVERAGE"):
            where = f"{mutation.findtext('mutatedClass')}#{mutation.findtext('mutatedMethod')}:{mutation.findtext('lineNumber')}"
            survivors.append(f"{status} {where} {mutation.findtext('description') or mutation.findtext('mutator')}")
    return {"killed": killed, "survivors": survivors}


def run_all(
    clone: Path, base_sha: str, issue_type: str, scratch: Path, logs: Path, java_home: str, timeout_min: int, check_pit: bool = True
) -> Tuple[List[Gate], dict, List[str]]:
    if git(clone, "status", "--porcelain"):
        return [Gate("clean-tree", "fail", "the working tree has uncommitted changes; commit or discard them")], {}, []
    entries = changes(clone, base_sha)
    if not entries:
        return [Gate("commits", "fail", f"no commits beyond {base_sha[:12]}")], {}, []
    forbidden, human = policy.classify_paths(entries)
    if forbidden:
        return [Gate("diff-policy", "fail", "forbidden paths: " + ", ".join(forbidden))], {}, human
    scan = secret_scan(clone, base_sha)
    if scan.status == "fail":
        return [scan], {}, human
    gates = [Gate("diff-policy", "pass", "; ".join(human)), scan]
    if any(p.startswith(BUILD_INPUTS) for _, p in entries):
        gates += fresh_verify(clone, scratch / "verify", logs, java_home, timeout_min)
    gates.append(repo_checks(clone, logs))
    if issue_type == "bug":
        gates.append(reverse_verify(clone, base_sha, entries, scratch / "reverse", logs, java_home, timeout_min))
    held = policy.local_only_paths(entries)
    if held:
        gates.append(Gate("hold", "hold", "workflow changes are never pushed unattended: " + ", ".join(held)))
    if not check_pit:
        return gates, {}, human
    pit, summary = pit_gate(clone, entries)
    gates.append(pit)
    return gates, summary, human


def failures(gates: Sequence[Gate]) -> List[Gate]:
    return [g for g in gates if g.status == "fail"]
