#!/usr/bin/env python3

import argparse
import importlib
import importlib.util
import sys
import unittest
from pathlib import Path


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--worktree", required=True, type=Path)
    parser.add_argument("--verifier", required=True, type=Path)
    args = parser.parse_args()
    sys.path.insert(0, str(args.worktree.resolve() / "scripts"))
    spec = importlib.util.spec_from_file_location("promoted_reverse_verifier", args.verifier.resolve())
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    names = (
        "tests.test_c0001_wrong_policy_field",
        "tests.test_c0002_non_object_policy",
        "tests.test_c0003_absolute_input_path",
        "tests.test_c0004_nested_input_symlink",
    )
    loader = unittest.defaultTestLoader
    suite = unittest.TestSuite()
    for name in names:
        module = importlib.import_module(name)
        if hasattr(module, "verify_run"):
            module.verify_run = verifier
        elif hasattr(module, "_fixture") and hasattr(module._fixture, "verify_run"):
            module._fixture.verify_run = verifier
        else:
            raise RuntimeError(f"{name} does not expose its verifier module")
        suite.addTests(loader.loadTestsFromModule(module))
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    sys.exit(main())
