#!/usr/bin/env bash
# Re-run the B and C batches, the pair used for the post-review final replay.
# Set PIT_AUDIT_OUT to choose one archive directory for both batches.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$here/../../../.." && pwd)"
out_dir="${PIT_AUDIT_OUT:-tmp/pit-audit-$(date -u +%Y%m%d)}"

"$repo_root/scripts/run-pit-core.sh" --batch B --out "$out_dir" "$@"
"$repo_root/scripts/run-pit-core.sh" --batch C --out "$out_dir" "$@"
