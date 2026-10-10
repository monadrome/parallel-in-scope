#!/usr/bin/env python3
"""Entry point for the optional issue -> triage -> agent runner; see design/issue-automation.md."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from issue_agent.cli import main

if __name__ == "__main__":
    sys.exit(main())
