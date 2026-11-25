# Remove TaskType and workload-fit screening: review round 4 (PIT record and Javadoc rewrap)

Review `f020503de120d5bcb525d568f90c02f6f8bcbe2c..758b7df6` read-only. The implementer is Claude
(Opus). Do not edit files, run Maven, or commit. You may run read-only commands: `git`,
`gzip -cd`, `grep`, `shasum -a 256 -c`, and `python3 -I` on the archived scripts from inside the
archive directory. `verification/contract-tests/` is out of scope.

## The commits

1. `99a22c28 docs: archive TaskType removal PIT evidence` adds the record
   `verification/pit-audit/2026-11-24-remove-tasktype.md` and the archive directory
   `verification/pit-audit/2026-11-24-remove-tasktype/`: README, raw head XML, gzipped base,
   early, and focused XML, gzipped logs, run-meta, comparisons, three helper scripts, and
   SHA256SUMS. About 1.4 MB of the diff is raw XML. Do not page through it; query the XML with the
   archived scripts, `gzip -cd`, or `grep`.
2. `758b7df6 docs: rewrap GroupStart adoption Javadoc` changes line breaks in one Javadoc
   paragraph of `core/src/main/java/io/github/monadrome/parallelinscope/GroupStart.java`.

Rounds 1 to 3 reviewed the removal itself (`f093bb12..f020503d`); their findings are settled and
must not be re-reported.

## Policy

- `verification/pit.md`: raw statuses, dispositions, and the compact record.
- `.agents/skills/contract-first-assurance/adapters/vformation.yaml` (`mutation_policy`) and
  `.agents/skills/contract-first-assurance/references/mutation-dispositions.md`: the
  `EXPECTED_MUTATION_LIVENESS` exception and its required evidence.
- `.agents/skills/project-delivery/references/assurance.md`, "Skip eligible": when later commits
  leave an assurance run frozen at `f020503d` valid.

## What to check

1. The record and README against the evidence: counts, comparisons, attributions, and every claim
   that a comparison reproduces from the archive.
2. The dispositions for the changed classes. Check the four equivalence arguments
   (`ParRuntime.installGlobal` removed `checkState`, `ParRuntime.ticker` null return,
   `TaskNode.hashCode` zero return, `SubmissionAwareBlockingQueue` constructor boundary) against
   the head source. For the last one, the delegate is `ResizableBlockingQueue(int)` in
   `/Applications/dev_conf/repo/io/github/monadrome/blocking-queues/0.1.0/blocking-queues-0.1.0-sources.jar`.
   Check the unreachable claim for `ParRuntime.releases()` and the contract, impact, and next-action
   statements of the three retained gaps. Report any equivalence claim you can refute with a
   concrete input or interleaving.
3. The timeout classification. Approve or reject each of these four `TIMED_OUT` entries as
   `EXPECTED_MUTATION_LIVENESS`, using `comparisons/par-runtime-timeouts.txt`,
   `reports/focused-par-runtime.xml.gz`, the logs, and the source:
   - (a) `ParRuntime.awaitQuiescence`: removed `Monitor::leave` in the zero-budget branch;
   - (b) `ParRuntime.enterAdmission`: negated open-runtime return;
   - (c) `ParRuntime.lambda$trackExecutions$0`: removed `Monitor::leave`;
   - (d) `ParRuntime$DispatchingScheduledExecutorService.lambda$schedule$0`: removed
     `ExecutorService::execute`.
   The adapter requires a repeatable focused rerun, passing unmutated tests, complete XML and
   logs, a stated source-level liveness invariant, and approval by a maintainer or an independent
   reviewer. You are the independent reviewer.
4. The scope. The record defines the change scope as the nine edited classes and uses the whole
   root package as an expansion that claims only "no lost evidence relative to base". Say whether
   that scope and its rationale satisfy `verification/pit.md`.
5. Skip eligibility. Are both commits documentation-only with respect to library requirements, so
   that the assurance run frozen at `f020503d` stays valid? The second commit must change line
   breaks only.
6. Prose: symbols instead of source line numbers in durable prose, English, and working links.

## Output

In `summary`, give one line for each of 3(a) to 3(d) (`APPROVE` or `REJECT`, with the reason), one
line for item 4 (`ACCEPT` or `REJECT`), and one line for item 5 (`CONFIRM` or `REJECT`). Report every
rejection and every defect as a finding tied to a file and a symbol or line, with a concrete
failure scenario.

## Verification already run

`shasum -a 256 -c SHA256SUMS` passes. Every file in `comparisons/` reproduces byte for byte from
the archived inputs. `check-doc-refs.py`, `check-local-links.py`, `check-english-docs.py`, and
`mvn -pl core spotless:check` pass.
