#!/usr/bin/env bash
# Re-run all five batches of the archived audit with the shared core PIT runner.
# Set PIT_AUDIT_OUT to choose the archive directory; extra arguments are forwarded.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../../../.." && pwd)"
out_dir="${PIT_AUDIT_OUT:-tmp/pit-audit-$(date -u +%Y%m%d)}"

exec "$repo_root/scripts/run-pit-core.sh" --batch all --out "$out_dir" "$@"
