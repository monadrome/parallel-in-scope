# Verification summary

- Frozen package and adapter: contract-first-assurance 1.5.2 with adapter vformation 1.5.2
  (`inputs/package.yaml`, `inputs/adapter.yaml`). Base `f093bb12f7695db32eaeb1e7e89cba2539944db7`;
  head and finalHead `f020503de120d5bcb525d568f90c02f6f8bcbe2c`, a clean worktree. The manifest
  records the regression suite root in its canonical form, `verification/contract-tests`; the
  adapter file spells it with a trailing slash.
- Frozen scope: `8b883a1a` (`feat!`), `e57602a8` (`docs`), and `f020503d` (`test`). Review rounds 1
  and 2 ran on the pre-rebase commits `07f83919` and `66d22906`, which have the same stable patch IDs
  as `8b883a1a` and `e57602a8`.
- Later commits: `99a22c28` and `758b7df6` (confirmed skip-eligible in review round 4),
  `2f73397f`, `ac95c948`, and `3ce96f08` (confirmed in round 6), and `6fb1ad34` (confirmed in round 7).
- Steps 011 to 041 ran in the verification worktree `.worktrees/rwf-assurance-verify` at
  `f020503d`, with Maven offline and JDK 25.0.4.1 unless a step names another JDK. Step 010 and the
  promotion steps 045 to 056 ran in the implementation worktree. Steps 045 to 053 ran at
  `2f73397f` plus the promoted files, and steps 054 to 056 at `6fb1ad34`. `logs/commands.jsonl` records every argv, working directory,
  environment, exit status, and log.
- Candidate provenance: PASS. Generation attempt 2 (`logs/002-*`) ran a fresh, ephemeral OpenAI
  Codex 0.169.0 with provider magpie and model deepseek/deepseek-v4-pro at medium effort. It ran in
  `/tmp/rwf-assurance-gen-f020503d`, which held only the generator README, the frozen contract, the
  sanitized API, and the command notes. It sealed sixteen candidates
  (`logs/002-sealed-candidates.sha256`). Attempt 1 lost its gateway connection mid-reply, and none
  of its output was used.
- Candidate compilation: four candidates needed a compile-only adaptation
  (`adaptation/compile-adaptation.diff`). 003, 004, and 016 wrapped a checked
  `InterruptedException` inside a `Function` body, and 009 lacked an import. Log 011 is the failing
  first compile and log 012 the passing adapted compile.
- Candidate tests on the real implementation (log 013): 15 of 16 pass in both surefire executions.
  003 fails in the `lincheck-test` execution because of a wrong assumption.
- Classification (`candidate-status.csv`): new coverage 005, 012, and 013; duplicate coverage 001,
  002, 004, 006, 007, 008, 009, 010, and 014; wrong assumption 003; insufficient oracle 011 and 016;
  unstable 015. 015 was first classified new coverage and was reclassified after review round 6, and
  its row keeps both states.
- Reverse verification: PASS (`reverse-verification/README.md`). Candidates 005, 012, 013, and 015
  each fail under their contrary patch (logs 015, 019, 023, and 027). Each patch reverts cleanly, and
  `git diff --exit-code` exits 0 after each revert. All four candidates pass together on the
  restored head (log 030).
- Full suite: PASS (logs 031 and 032). 3396 `default-test` and 2 `lincheck-test` tests.
- Formatting: PASS (logs 033 and 034).
- Race claims: PASS (log 035).
- Documentation: PASS (log 039). Doc references, local links, English docs, ADRs, design routes,
  proposals, the project baseline, worktree skills, verification integrity, the skill package, and
  a strict MkDocs build.
- Consumer: PASS. Log 036 resolves version 0.5.0-SNAPSHOT, and log 037 installs it. The Maven
  Central consumer and the demo then pass on JDK 8, 17, 21, and 25 (log 038), and on JDK 11 in
  `maven:3.9.11-eclipse-temurin-11` (log 041). The first JDK 11 attempt (log 040) failed in the demo's
  `G6_BatchDbQueryTest` with a negative elapsed time after the container VM's wall clock stepped.
  The unchanged rerun passed. The installed jar's SHA-256 matched in both environments.
- Refactoring review: PASS with a coverage limitation (`evidence/refactoring-review/report.md`).
  Six kimi detectors reported no findings. The detector cap left per-file shards C 2-9, D 2-5,
  S 2-5, V 2-4, and T 2-8 unrun, and the adversarial review covered those files.
- PIT: PASS with accepted exceptions (`evidence/pit/2026-11-24-remove-tasktype.md`, committed as
  `verification/pit-audit/2026-11-24-remove-tasktype.md` in `3ce96f08`).
  - The changed classes have 292 mutants: 280 `KILLED`, 5 `SURVIVED`, 3 `NO_COVERAGE`, and 4
    `TIMED_OUT`. Their dispositions are three equivalents, one unreachable, and four pre-existing
    gaps.
  - On 2026-11-25 the maintainer accepted the four gaps as residual nonblocking limitations.
  - The four `TIMED_OUT` entries are accepted as `EXPECTED_MUTATION_LIVENESS`, approved in review
    round 4.
  - The 28 `TIMED_OUT` entries in unchanged classes keep their base raw status and lie outside the
    change scope.
- Independent review: PASS. Every round used a model other than the implementer's:
  - Round 1 (`07f83919`): kimi reported two P3 findings and codex one P3 finding, all fixed in
    `e57602a8`. The kiro seat failed because it was not logged in.
  - Rounds 2 (`66d22906`) and 3 (`f020503d`): kimi and codex reported no findings.
  - Round 4 (`758b7df6`): kimi reported one P2 finding, fixed in `2f73397f`.
  - Round 5 (`2f73397f`): kimi reported no findings.
  - Round 6 (`3ce96f08`): kimi passed the promotion integrity of all four targets and confirmed
    skip eligibility, the promotion message, and the PIT disposition. It reported two P2 findings in
    C-0005: the registration and cycle-warning oracles accept asynchronous records from an earlier
    phase. The promoter checked both against the source, and `6fb1ad34` withdrew C-0005.
  - Round 7 (`6fb1ad34`): kimi reported no findings. It judged both round-6 defects real and
    resolved by the withdrawal, and found the remaining targets, the run records, and steps 054
    to 056 consistent. It also confirmed the commit skip-eligible, with an accurate message.
- Promotion: PASS. C-0002 (from 005), C-0003 (012), and C-0004 (013) each occupy a unique target
  file, committed in `ac95c948` and unchanged since. The committed bytes, the archives in
  `promotions/`, and the snapshots in `promotion-targets/` are identical. `ac95c948` also promoted
  015 as C-0005, and `6fb1ad34` removed it. Its `promotion-status.csv` row is `blocked` with the
  reason, its archive copy stays in `promotions/` as evidence, and its snapshot is removed.
- Regression gates: PASS. Steps 054 (metadata), 055 (tests: 3396 + 2 core and 4 suite tests), and
  056 (execution) ran on the final suite at `6fb1ad34`, with argv equal to the adapter's
  `regressionSuite`. Steps 051 to 053 (5 suite tests) are the passing runs before the withdrawal,
  and step 048 checked the promoted files' formatting. Steps 045 to 047, 049, and 050 are earlier
  passing runs, recorded before and immediately after the provenance edit.
- Final outcome: PASS WITH ACCEPTED EXCEPTIONS. All eight required gates pass. The manifest records
  the four accepted `TIMED_OUT` exceptions with their evidence and approval.
