# Core PIT Audit — 2026-10-31

This archive preserves the definitive core-scope mutation audit for
`parallel-in-scope`. It covers production classes directly in
`io.github.monadrome.parallelinscope`; the independent `queue` subpackage is outside
the mutation scope, though its tests still run in the unmutated baseline.

## Provenance

- Production input revision: `a3e750f9` on `dev/v0.4.0`, equal to
  `origin/dev/v0.4.0` when the audit began.
- Final test revision: `2b2e3ad0`. The intervening commits `29ed543c` and
  `2b2e3ad0` changed tests only, so the production bytecode under mutation remained
  at `a3e750f9`.
- Working tree: clean at the start of each final batch.
- Build JDK: 25.0.2 (Homebrew). Library bytecode target: Java 8; test target: Java 11.
- PIT: 1.30.1 with `pitest-junit5-plugin` 1.2.3, 8 threads,
  `timeoutConstant=60000`, `timeoutFactor=1.25`.
- Scope: root package only, selected explicitly by the A–E class patterns in
  `inventory.md`. `VOID_METHOD_CALLS` is included in every batch.
- History input: none. Every final batch performed a fresh full-coverage analysis.

The XML files are the raw evidence. The gzip files are byte-for-byte compressed
copies of the complete console logs from the same batches.

## Definitive Counts

| Batch | KILLED | SURVIVED | NO_COVERAGE | TIMED_OUT | Total |
|---|---:|---:|---:|---:|---:|
| A | 230 | 5 | 0 | 0 | 235 |
| B | 245 | 14 | 3 | 4 | 266 |
| C | 546 | 30 | 5 | 5 | 586 |
| D | 262 | 16 | 2 | 12 | 292 |
| E | 300 | 4 | 8 | 5 | 317 |
| **Total** | **1583** | **69** | **18** | **26** | **1696** |

Killed ratio: **1583 / 1696 = 93.3373% (93.34%)**.

These are raw PIT statuses, not the score printed in PIT's console summary. PIT adds
`TIMED_OUT` mutants to the "Killed" headline, so the log headline and the XML
`KILLED` count differ by the batch's timeout count.

## Correction To Commit 29ed543c

Commit `29ed543c` records the result as **1581/1696 (93.2%)** with **28
TIMED_OUT**. That summary is stale. The definitive XMLs preserve **1583 KILLED,
69 SURVIVED, 18 NO_COVERAGE, and 26 TIMED_OUT**, totaling **1696** and **93.34%**.
The pushed commit is intentionally not amended; this README, `dispositions.md`, and
the XMLs supersede its summary. The follow-up review fixes in `2b2e3ad0` are
reflected in the final B and C runs.

Every `SURVIVED` and `NO_COVERAGE` entry has an E/D/G disposition in
`dispositions.md`. The 26 `TIMED_OUT` entries remain raw and inconclusive by policy;
they were not relabeled as kills.

## Evidence Layout

```text
verification/pit-audit/2026-10-31/
├── README.md
├── SHA256SUMS
├── inventory.md
├── dispositions.md
├── reports/final-batch-{A..E}.xml
├── logs/final-batch-{A..E}.log.gz
└── scripts/
    ├── rerun-final-batches.sh
    └── rerun-final-batches-b-c.sh
```

The raw final logs total about 17 MB; gzip reduces them to about 230 KB without
losing content. To read one, use `gzip -cd logs/final-batch-A.log.gz`, or
`gzcat` on macOS.

## Reproduction

Run from the repository root. The shared runner selects the same A–E partition and
mutator set, writes one directory per batch, and refuses to overwrite existing
artifacts without `--force`:

```bash
./scripts/run-pit-core.sh --help
./scripts/run-pit-core.sh --batch A --out tmp/pit-audit-replay
./scripts/run-pit-core.sh --batch all --out tmp/pit-audit-replay
```

Each `OUT/batch-<name>/` contains `mutations.xml`, `pit.log`, and `run-meta.txt`.
The metadata records the commit, working-tree status, JDK, scope, mutators, exact
command, start/end timestamps, exit status, and whether the XML was archived. The
runner removes `target/pit-reports/mutations.xml` before each batch, so a failed
compile cannot archive an earlier run's report as current evidence. It uses
`mvn -o`, so dependencies must already be installed.

To reproduce the historical evidence, run the script against the final audit
revision (`2b2e3ad0`) rather than a later branch tip. For example, in a separate
worktree:

```bash
git worktree add /tmp/pis-pit-audit 2b2e3ad0
cp scripts/run-pit-core.sh /tmp/pis-pit-audit/scripts/run-pit-core.sh
cd /tmp/pis-pit-audit
./scripts/run-pit-core.sh --batch all --out tmp/pit-audit-replay
```

Recompute total counts from XML without relying on PIT's headline:

```bash
python3 - <<'PY'
from collections import Counter
from pathlib import Path
import xml.etree.ElementTree as ET

counts = Counter()
for report in Path("verification/pit-audit/2026-10-31/reports").glob("final-batch-*.xml"):
    counts.update(m.get("status") for m in ET.parse(report).getroot().findall("mutation"))
print(dict(counts), sum(counts.values()))
PY
```

Verify the committed files before using them as evidence:

```bash
cd verification/pit-audit/2026-10-31
shasum -a 256 -c SHA256SUMS
```

The archive-specific scripts are thin wrappers around the shared runner:
`rerun-final-batches.sh` replays all five batches, while
`rerun-final-batches-b-c.sh` replays the post-review B/C pair. Set `PIT_AUDIT_OUT`
to select their archive directory; `--force` and `--help` are forwarded.

## Limitations

- The audit mutates the root package only. Queue behavior has its own contract and
  test scope and is not covered by these counts.
- PIT identifies mutants by source line in its XML. This prose uses class, method,
  and mutation descriptions where possible; the XML remains the durable locator.
- The local `tmp/pit-audit-20261031/` workspace also contains superseded
  intermediate reports and the original uncompressed logs. It is not committed and
  is not part of this archive's evidence set.
