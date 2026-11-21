#!/usr/bin/env python3
"""Compile Java 8 adoption consumers with the core compiler and ordinary javac."""

from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "verification/adopt-compile-contract"
NS = "{http://maven.apache.org/POM/4.0.0}"


def probe_pom(directory: Path, error_prone: bool) -> None:
    """Reuse resolved core dependencies and compiler settings without touching its outputs."""
    ET.register_namespace("", NS[1:-1])
    tree = ET.parse(ROOT / "core/pom.xml")
    project = tree.getroot()
    shutil.copyfile(ROOT / "pom.xml", directory.parent / "pom.xml")
    build = project.find(f"{NS}build")
    for name, value in {
        "directory": directory / "target",
        "outputDirectory": ROOT / "core/target/classes",
        "testSourceDirectory": directory / "src",
    }.items():
        ET.SubElement(build, NS + name).text = str(value)
    for plugin in build.findall(f"{NS}plugins/{NS}plugin"):
        if plugin.findtext(NS + "artifactId") != "maven-compiler-plugin":
            continue
        configuration = plugin.find(NS + "configuration")
        args = configuration.find(NS + "compilerArgs")
        if not error_prone:
            for arg in list(args):
                if arg.text.startswith("-Xplugin:ErrorProne"):
                    args.remove(arg)
            configuration.remove(configuration.find(NS + "annotationProcessorPaths"))
            ET.SubElement(args, NS + "arg").text = "-proc:none"
        ET.SubElement(args, NS + "arg").text = "-Xlint:deprecation"
    tree.write(directory / "pom.xml", encoding="utf-8", xml_declaration=True)


def verify_probe(directory: Path, error_prone: bool, forbidden: bool, logs: Path) -> None:
    """Require each forbidden call's own diagnostic; unrelated compiler failures cannot pass."""
    source = FIXTURES / ("ScheduledCalls.java" if forbidden else "FiniteCalls.java")
    directory.mkdir()
    (directory / "src").mkdir()
    shutil.copyfile(source, directory / "src" / source.name)
    probe_pom(directory, error_prone)
    command = ["mvn", "-B", "-ntp", "-f", str(directory / "pom.xml"),
               "-Dmaven.compiler.testRelease=8", "compiler:testCompile"]
    result = subprocess.run(command, cwd=ROOT, env={**os.environ, "MAVEN_BASEDIR": str(ROOT),
                                 "MAVEN_OPTS": os.environ.get("MAVEN_OPTS", "") + " -Duser.language=en -Duser.country=US"},
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=180)
    (logs / f"{directory.name}.log").write_text("Command: " + repr(command) + "\n" + result.stdout)
    expected_failure = error_prone and forbidden
    if (result.returncode != 0) != expected_failure:
        raise RuntimeError(f"{directory.name}: unexpected compiler exit {result.returncode}")
    if forbidden:
        check = r"\[DoNotCall\]" if error_prone else "has been deprecated"
        lines = {int(n) for n in re.findall(
            rf"ScheduledCalls\.java:\[(\d+),\d+\].*{check}", result.stdout)}
        expected = {i for i, line in enumerate(source.read_text().splitlines(), 1)
                    if "return " in line and ".adopt(" in line}
        if len(expected) != 4 or lines != expected:
            raise RuntimeError(f"{directory.name}: expected diagnostics at {expected}, got {lines}")
        if error_prone:
            diagnostics = re.findall(r"ScheduledCalls\.java:\[\d+,\d+\].*\[DoNotCall\]([^\n]*)", result.stdout)
            for diagnostic in diagnostics:
                for hint in ("one-shot", "ListenableFuture", "periodic"):
                    if hint not in diagnostic:
                        raise RuntimeError(f"{directory.name}: missing migration guidance: {hint}")
    print(f"PASS: {directory.name}")


def main() -> None:
    """Exercise all four overloads and their supported migration with and without Error Prone."""
    if not (ROOT / "core/target/classes/io/github/monadrome/parallelinscope/GroupStart.class").is_file():
        raise RuntimeError("compile core before running the adoption compiler contract")
    logs = ROOT / "target/adopt-compile-contract"
    logs.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="adopt-compile-") as temporary:
        for error_prone in (True, False):
            for forbidden in (False, True):
                name = ("error-prone" if error_prone else "javac") + ("-scheduled" if forbidden else "-finite")
                verify_probe(Path(temporary) / name, error_prone, forbidden, logs)


if __name__ == "__main__":
    main()
