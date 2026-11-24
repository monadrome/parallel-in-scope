"""Verify the reproducibility metadata and hashes of an assurance run."""
from __future__ import annotations
import argparse
import csv
import hashlib
import json
import re
import sys
from pathlib import Path
REQUIRED_FILES = ('contract.md', 'contract-gaps.md', 'candidate-status.csv', 'promotion-status.csv', 'verification-summary.md', 'report.md')
REQUIRED_DIRECTORIES = ('inputs', 'logs', 'candidates', 'promotions', 'reverse-verification')
VALID_STATUSES = {'pass', 'fail', 'blocked', 'inconclusive', 'skipped'}
FINAL_GATE_RUN_STATUSES = {'PASS': 'pass', 'PASS WITH ACCEPTED EXCEPTIONS': 'pass', 'FAIL': 'fail', 'BLOCKED': 'blocked', 'INCONCLUSIVE': 'inconclusive'}
PASSING_FINAL_GATES = {'PASS', 'PASS WITH ACCEPTED EXCEPTIONS'}
ACCEPTED_EXCEPTION_FIELDS = ('phase', 'rawStatus', 'disposition', 'accepted', 'evidence', 'approvedBy')
PROMOTION_FIELDS = ('candidate_id', 'source_contract_id', 'contract_id', 'target_path', 'archive_path', 'status', 'reason')
PROMOTION_CONTRACT_RE = re.compile('C-[0-9]{4}')
COMMIT_ID_RE = re.compile('(?:[0-9a-f]{40}|[0-9a-f]{64})')

def gate_name_errors(value, field: str) -> list[str]:
    ...

def canonical_path(value: str) -> bool:
    ...

def sealed_candidates(run_dir: Path) -> tuple[set[str], list[str]]:
    ...

def verify_promotions(run_dir: Path, manifest: dict) -> tuple[list[str], list[dict]]:
    ...

def verify_promotion_execution(manifest: dict, rows: list[dict]) -> list[str]:
    ...

def safe_path(run_dir: Path, relative: str) -> Path:
    ...

def sha256(path: Path) -> str:
    ...

def verify(run_dir: Path) -> list[str]:
    ...

def main() -> int:
    ...
if __name__ == '__main__':
    sys.exit(main())
