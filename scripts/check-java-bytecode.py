#!/usr/bin/env python3
"""Verify that compiled class files use the requested JVM class-file version."""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path


JAVA_RELEASE_TO_MAJOR = {
    8: 52,
    9: 53,
    10: 54,
    11: 55,
    12: 56,
    13: 57,
    14: 58,
    15: 59,
    16: 60,
    17: 61,
    18: 62,
    19: 63,
    20: 64,
    21: 65,
    22: 66,
    23: 67,
    24: 68,
    25: 69,
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("classes", type=Path, help="directory containing compiled .class files")
    parser.add_argument("--release", type=int, required=True, help="expected Java release")
    return parser.parse_args()


def class_major(path: Path) -> int:
    with path.open("rb") as stream:
        header = stream.read(8)
    if len(header) != 8 or header[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError(f"{path} is not a valid class file")
    _, major = struct.unpack(">HH", header[4:])
    return major


def main() -> int:
    args = parse_args()
    try:
        expected = JAVA_RELEASE_TO_MAJOR[args.release]
    except KeyError:
        print(f"unsupported Java release: {args.release}", file=sys.stderr)
        return 2

    if not args.classes.is_dir():
        print(f"class directory does not exist: {args.classes}", file=sys.stderr)
        return 2

    failures = []
    class_files = sorted(args.classes.rglob("*.class"))
    for path in class_files:
        actual = class_major(path)
        if actual != expected:
            failures.append((path, actual))

    if failures:
        for path, actual in failures:
            print(f"{path}: expected major version {expected}, found {actual}", file=sys.stderr)
        return 1

    print(f"verified {len(class_files)} class files at Java {args.release} (major {expected})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
