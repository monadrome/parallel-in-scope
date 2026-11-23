# Command Reference

## Commands

Run from the repository root, except the worktree skill check, which runs inside the new
worktree before the first skill invocation there.

```bash
mvn test -Dtest='ClassName#methodName' # targeted test; replace class and method
mvn test                              # all tests
mvn spotless:apply                    # format Java sources
mvn spotless:check                    # verify formatting without writing; CI runs this
mvn clean verify                      # tests + package checks; not a release build
mvn -Pspotbugs -Dmaven.test.skip=true verify  # opt-in SpotBugs High gate; not in default CI
python3 scripts/check-doc-refs.py     # documentation must not depend on hashes or line numbers
python3 scripts/check-adr.py          # ADR metadata fields and required sections
python3 scripts/check-design-routes.py  # design route tables, proposal identity, beta markers
python3 scripts/check-proposal.py     # proposal abstracts, id uniqueness across proposal/ and archive/, content bans
python3 scripts/check-local-links.py  # relative Markdown links resolve; no live links into design/archive/
python3 scripts/check-english-docs.py  # English locations contain no Han characters
python3 scripts/check-java-conventions.py  # new library-source convention violations; see verification/java-conventions.md
python3 scripts/refactoring-review.py plan  # refactoring-review gate and detector plan for the committed change
python3 scripts/process-metrics.py record --topic <topic> --route <route>  # delivery record; see verification/delivery-ledger.md
python3 scripts/process-metrics.py report  # aggregate delivery records: review rounds, rule findings, gate failures
scripts/run-pit-core.sh --batch <A|B|C|D|E|all> --out <archive-dir>  # default core PIT partition; archives XML, log, monitor data, and metadata
python3 scripts/watch-delivery-ci.py <sha>  # watch the pushed tip's CI runs to a terminal result; attributes runs by head_sha
python3 .agents/skills/contract-first-assurance/scripts/verify_package.py  # verify the versioned assurance skill bundle
(cd .worktrees/<topic> && python3 scripts/check-worktree-skills.py)  # verify the new worktree's required skills; run there, not in the shared checkout
python3 scripts/check-project-baseline.py  # verify the development-line baseline is consistent
python3 .agents/skills/contract-first-assurance/scripts/verify_run.py --run-dir <run-dir>  # verify assurance inputs, logs, and hashes
```

The `contract-first-assurance` skill is maintained as a versioned local bundle.
Its entrypoint and four phase skills remain direct children of `.agents/skills/`
for tool discovery; `.agents/skills/contract-first-assurance/package.yaml`
declares their shared version and sibling dependencies. The entrypoint reads
repository-specific build, test, and mutation commands through its host adapter,
so it must not grow project-specific command assumptions. Run the package
verifier after changing any member of the bundle. The current repository adapter
is `.agents/skills/contract-first-assurance/adapters/vformation.yaml`; a future
standalone agent-skills checkout should provide its own adapter instead of
copying these repository paths into the portable phases.

Project skills required by repository workflows are listed in
`.worktreeinclude.yaml` and must be committed under `.agents/skills/`. The
skill check inspects the checkout containing the script, so a run in a shared
checkout proves nothing about the new worktree. It rejects missing, untracked,
and symlinked skill directories; it never copies files from a parent checkout.
This makes the Git commit the source of truth for worktree availability.

Core PIT partitions go through `scripts/run-pit-core.sh`, the durable default:
it archives each batch's `mutations.xml`, `pit.log`, `process-monitor.log`, and
`run-meta.txt` under `<archive-dir>/batch-<name>/`. Procedure, durability, and
dispositions: [PIT verification](../../../../verification/pit.md).

The current development line and its upstream ref are declared once in
`.agents/project-baseline.json`. Review gates and their documentation are
checked with `python3 scripts/check-project-baseline.py`; update that manifest
and all reported consumers together when the development line changes.

Assurance runs must keep their input snapshots, command logs, resolved commit
IDs, package and adapter versions, and SHA-256 artifact hashes in the run
directory. `verify_run.py` is the final reproducibility gate; a report without a
valid `run-manifest.json` is incomplete.
