---
name: contract-first-assurance
description: Contract-first independent verification workflow. Use when the user asks to independently verify a PR, generate tests from contracts, check behavioral gaps between implementation and requirements, re-check cancellation/concurrency/lifecycle semantics, or produce an auditable verification report; can also be invoked manually via /contract-first-assurance. Freezes the contract first, then generates candidate tests in an isolated context, and classifies verification results on the real implementation.
compatibility: Requires git, an isolated worktree, and a fresh agent context; repository build and mutation commands come from the host adapter.
version: "1.4.0"
---

# Contract-First Independent Assurance

## Package and host resolution

The entry point and the four phase skills belong to one versioned package. First read
`package.yaml` in the directory containing this file, record that directory as `SKILL_ROOT`,
then resolve the host configuration in this order:

1. the run-specific adapter passed by the caller;
2. the in-repository assurance configuration;
3. the applicable `AGENTS.md`, design/ADR, and verification documents;
4. the generic rules in `SKILL_ROOT/references/host-adapter.md`.

The host adapter supplies the workspace, change scope, baseline, build commands, format checks,
mutation policy, regression-suite promotion commands, and output directories; this skill assumes
nothing about Java, Maven, Gradle, npm, or PIT. When a required adapter input is missing, keep
`BLOCKED` or `INCONCLUSIVE` evidence instead of guessing commands. Mutation raw statuses and verification conclusions must
be recorded separately. Read
[references/mutation-dispositions.md](references/mutation-dispositions.md) for how to accept
well-evidenced mutation-induced liveness failures while preserving the `TIMED_OUT` raw status.

This round's `RUN_DIR` is the run directory resolved by the adapter, defaulting to
`.contract-first-assurance/<run-id>` under the repository root. All phases must use the same
`RUN_DIR`, and record the resolved adapter values in `run-manifest.json` and
`verification-summary.md` so an independent worktree can reproduce the run. The run-manifest
and hashing rules are in
[references/reproducible-runs.md](references/reproducible-runs.md).

Before starting phase one:

1. resolve and freeze the package, adapter, base/head commits, and dirty state;
2. copy the contract sources, the sanitized API, the adapter configuration, and the dirty
   worktree patch (if any) into `${RUN_DIR}/inputs/`;
3. create `${RUN_DIR}/logs/`, saving every command's exact argv, exit code, and stdout/stderr
   paths;
4. write the initial `run-manifest.json` with frozen metadata and SHA-256 for the inputs;
   result fields and final-artifact hashes are added at finalization.

## Goal

Turn one PR's implementation changes into an auditable contract-verification flow:

```text
PR/worktree changes
  ↓
contract extraction and gap marking
  ↓
an independent test agent generates tests from the contract
  ↓
compile and execute on the real implementation
  ↓
behavior-level deduplication and failure classification
  ↓
reverse verification
  ↓
contract revision suggestions and code improvement suggestions
```

Once invoked, this skill advances the whole flow automatically. Do not repeatedly confirm each
step with the user unless an irremovable external blocker appears or AGENTS.md explicitly
requires a human decision.

## Invocation modes

This entry allows three invocation modes:

- **Autonomous invocation**: when the user asks to check PR quality, independently verify an
  implementation, generate tests from requirements or contracts, or find behavioral gaps
  between implementation and requirements, the model may invoke automatically based on the
  `description`. Plain "write tests", "fix a bug", or "do code review" triggers only when an
  independent-verification, contract-gap, or isolated-testing intent is explicitly present.
- **Manual invocation**: the user types `/contract-first-assurance`; the full flow runs
  regardless of the current request's wording.
- **Required route invocation**: a host workflow explicitly dispatches this entry when its
  assurance policy marks the change as requiring independent verification; the full flow and
  passing-report gate apply.

This skill's sub-flow skills have autonomous invocation off by default; they can only be
dispatched explicitly by this entry or invoked by the user typing their commands directly.

## Input scope

Priority order:

1. a PR URL, PR number, or commit range given by the user;
2. the current branch's diff against the target development line;
3. uncommitted changes in the current worktree.

Before starting, confirm the repository root, the current branch, the target baseline, and the
PR change scope. Do not include unrelated modules, historical changes, or the whole repository
in the contract by default.

If the PR is documentation-only, still extract the contract, but skip Java tests and mutation
testing, stating the skip reason.

The cross-platform switches for invocation modes are in
[references/invocation-modes.md](references/invocation-modes.md); host configuration and
reproducibility boundaries are in [references/host-adapter.md](references/host-adapter.md).

## Sub-flow dispatch

Read and execute the corresponding sub-flow explicitly per phase; do not let sub-flows compete
to trigger themselves through natural language:

- Contract extraction: read `${SKILL_ROOT}/../contract-extract/SKILL.md`;
- Isolated test generation: read `${SKILL_ROOT}/../isolated-test-generation/SKILL.md`;
- Result merging and contract diff: read `${SKILL_ROOT}/../contract-diff/SKILL.md`;
- Final report: read `${SKILL_ROOT}/../assurance-report/SKILL.md`.

Sub-flows must inherit this entry's run-id, artifact paths, and isolation constraints, but not
the main agent's conversation history.

## Phase one: extract the contract

First read the applicable `AGENTS.md`, the target module's contracts, relevant design/ADRs, and
the PR diff. Output a frozen contract document to:

```text
${RUN_DIR}/contract.md
```

Each contract contains at least:

- a stable `C-<number>` ID;
- trigger conditions and precondition state;
- normal results;
- exceptions, error codes, or outcomes;
- lifecycle and resource ownership;
- cancellation, deadline, context, executor, or queue semantics;
- observable assertions;
- the current evidence source (PR, Javadoc, design, existing tests);
- explicit uncertainties.

Express strength with MUST, MUST NOT, SHOULD, and MAY. Do not automatically write
implementation details into user contracts.

Also produce:

```text
${RUN_DIR}/contract-gaps.md
```

Classifying missing information into:

- missing exception types or validation order;
- missing lifecycle boundaries;
- missing concurrency interleavings or visibility guarantees;
- missing cancellation/failure attribution;
- missing input boundaries and nullability;
- missing distinction between final results and live observation;
- missing failure-handling and resource-cleanup requirements.

## Phase two: set up the isolated test environment

A genuinely isolated worktree or equivalent sandbox is mandatory.

### Context isolation is a hard constraint

The test subagent must start with a fresh context. Invocation must not inherit the main agent's
full session history, the implementation agent's conversation, the implementation agent's
analysis drafts, any prior test-generation process, or previous failure explanations. When
using collaboration tools, use a fresh context equivalent to `fork_turns: none`; do not use a
default launch mode that copies the parent session's history.

The main agent passes the test subagent exactly one minimal, frozen, auditable input package:

```text
contract.md
the sanitized API/type views it may read
the necessary build and test commands
the test output format and candidate ID rules
```

None of the following may enter the input package, nor may the subagent discover them through
paths or tools:

- the main agent's analysis, judgments, and conversation history;
- the implementation agent's prompts, reasoning, code drafts, or fix records;
- method bodies and private fields of the real production source;
- existing tests, the hidden regression suite, test reports, PIT reports, and previous failure
  classifications;
- other agents' candidate tests, assumptions, or `candidate-status.csv`;
- Git history, the parent worktree, the main checkout, or unfrozen material in hidden
  directories.

If the platform cannot guarantee a fresh context, this verification round must be marked
`BLOCKED` or `INCONCLUSIVE`; test generation within the same session must not be called
independent verification.

The test agent:

- receives only the frozen `contract.md`, the allowed API/type views, and necessary build
  information;
- does not read the implementation agent's reasoning, test drafts, existing tests, or the
  parent directory's source;
- does not read Git history to recover hidden implementations;
- does not modify production code;
- assigns each candidate test a stable ID such as `CONTRACT-TDD-001`;
- writes out the precondition state, operation sequence, observation points, assertions, and
  uncertainty notes.

When implementation details need hiding, use a Java AST or the target language's syntax tree to
generate sanitized sources. Keep types, signatures, generics, annotations, Javadoc, and public
constants; hide method bodies, private fields, and internal dispatch details. Do not use regex
replacement as the primary sanitization method.

When using subagents, prefer a context different from the implementation agent's. A different
model is optional; context and artifact isolation is the hard requirement.

Before launching a subagent, check that its working directory contains no real production
source, existing tests, or other agents' output. Only after the test agent finishes are the
candidate tests copied as one-way artifacts into the independent verification worktree; do not
pass the production implementation, failure logs, or corrected assertions from verification
back to the test agent to keep "fixing the independent tests".

## Phase three: generate tests

Generate the minimal test set relevant to the change from the contract, prioritizing:

- normal paths;
- input boundaries and combined illegal inputs;
- lifecycle transitions;
- failure and exception attribution;
- cancellation, deadline, context, executor binding;
- queue capacity, FIFO, blocking, and wakeup;
- concurrency interleavings and state machines;
- observable side effects, resource release, and execution exit.

The test agent must not copy the implementation's internal structure. Assertions should target
public behavior and the contract, not private fields, invocation counts, or the current
algorithm — unless those are themselves part of the contract.

Candidate tests, plans, and assumptions go to:

```text
${RUN_DIR}/candidates/
```

## Phase four: verify on the real implementation

Copy the candidate tests into the independent verification worktree and run them against the
real PR implementation:

1. run the candidate tests' compilation check first;
2. record every compilation failure;
3. when fixing test adaptation problems, never modify production code;
4. run the target tests;
5. judge each failure as a production defect, a wrong test assumption, timing instability, an
   environment problem, or an incomplete contract;
6. run the relevant existing tests, avoiding mixing the new tests with existing evidence;
7. finally run the full tests and format checks the project requires.

Real-implementation verification must happen after the test agent has finished and sealed its
candidates. The test agent must not keep modifying candidate tests after seeing the real
implementation's failure output; when adaptation is needed, the main agent or a separate
adaptation phase handles it, keeping the initial candidates and the adaptation diff.

Every candidate test must be written into:

```text
${RUN_DIR}/candidate-status.csv
```

Status may only use these values:

- `new coverage`: no existing test provides the same behavioral evidence;
- `duplicate coverage`: behavior, preconditions, and observation window equivalent to an
  existing test;
- `wrong assumption`: the test's derivation was reasonable, but does not match the current
  explicit contract;
- `compilation failure`: the API, generics, exceptions, or test-environment assumption does
  not hold;
- `run failure`: compiled, but a precondition, timing, or assertion does not hold;
- `unstable`: depends on undefined scheduling or time windows;
- `insufficient oracle`: the contract cannot derive a decidable expectation.

Failure classification must keep the initial state and the corrected final state; never report
only the final green result.

## Phase five: behavior-level deduplication

Never deduplicate by test names, file names, or coverage alone. Compare:

- inputs and precondition states;
- operation sequences;
- observation points;
- assertions and exception classification;
- concurrency interleavings;
- lifecycle phases;
- whether side effects, cleanup, or resource release are checked.

If a candidate test merely rewrites an existing test in another form, mark it `duplicate
coverage`. If it exposes a behavioral divergence undefined in the documentation, mark it
`wrong assumption` or `insufficient oracle`, and write it back to `contract-gaps.md`.

## Phase six: reverse verification

For every `new coverage` test, pick at least one corresponding production behavior for
temporary reverse verification:

- temporarily inject a behavior contrary to the contract; or
- use PIT, fault injection, state perturbation, or an equivalent controllable mutation;
- run the target test and confirm it fails as expected;
- restore the production files exactly;
- save the commands, exit statuses, and logs.

Reverse verification results go to:

```text
${RUN_DIR}/reverse-verification/
```

Leave no temporary mutation in the worktree. Mutation testing must follow the resolved
adapter's `mutation_policy` and its instructions, preserving raw mutation statuses; if the
adapter provides no applicable policy, mark that phase `BLOCKED` or `INCONCLUSIVE`. Timeouts,
memory errors, missing XML, or partial runs must not be reported as passing; only exceptions
satisfying the disposition rules and explicitly accepted may enter `PASS` at the gate layer,
with the raw status and exception record preserved.

## Phase seven: promote confirmed regressions

The main assurance agent owns promotion. Neither the isolated test generator nor the implementation
agent receives the resolved regression suite. If the main assurance agent also implemented the
change, a fresh assurance or maintenance context must own promotion; otherwise record it as
`BLOCKED`. Resolve `regression_suite` from the host adapter. If an otherwise eligible candidate
cannot be promoted because its path or commands are missing, record the promotion as `BLOCKED`
rather than guessing a repository layout or build command.

A candidate is eligible only when all of these hold:

- its final classification is `new coverage`;
- it passed on the real implementation;
- reverse verification produced the expected failure and restored the production source exactly;
- its frozen contract provides a decidable public observable expectation.

Write `${RUN_DIR}/promotion-status.csv` with this exact header:

```text
candidate_id,source_contract_id,contract_id,target_path,archive_path,status,reason
```

Every row has a non-empty reason and status `promoted` or `blocked`. For a promoted row, the
candidate ID is non-empty, unique, and sealed in `candidate-status.csv`; the source contract ID is
non-empty; and the allocated ID is `C-nnnn`. Target and archive paths use one canonical
repository-relative spelling with no empty, `.` or `..` components. The archive is under
`promotions/`, exists, and is SHA-256 hashed in the run manifest. Promoted candidate IDs, contract
IDs, target paths, and archive paths are unique. A header-only CSV is valid when no candidate is
eligible.

For each eligible candidate:

1. Allocate the next unused four-digit contract ID in the resolved suite. Record the candidate and
   its promotion disposition in the CSV.
2. Adapt only package and build integration. Preserve the precondition state, operation sequence,
   observation window, and assertions. Save the adapted test under `${RUN_DIR}/promotions/` and
   copy it to the suite, applying the suite's scoped contract-ID metadata convention. Contract IDs
   are unique within the persistent suite.
3. Run the suite's configured metadata check, clean test command, and execution-evidence check
   after all additions. Freeze their exact argv values in `adapter.regressionSuite`, record them as
   successful `regression-metadata`, `regression-tests`, and `regression-execution` commands, and
   add a passing `regression-tests` gate. A candidate is `promoted` only when all three pass and its
   archived copy is hashed in the manifest. Otherwise retain its adapted copy and failure evidence
   under `RUN_DIR`, and remove the unconfirmed new target file so the checkout contains no partial
   promotion.

Keep the sealed candidates unchanged and retain the adaptation diff for audit. When no candidate is eligible,
write the header-only promotion CSV, create an empty `promotions/` directory, and report the phase
as `SKIPPED` with the reason. Do not promote duplicate, unstable, wrong-assumption, or unverified
candidates to make the suite grow.

## Phase eight: output improvement suggestions

Finally produce:

```text
${RUN_DIR}/report.md
```

The report must answer:

1. which contracts the PR actually commits to;
2. which contracts lack exception, boundary, lifecycle, or concurrency details;
3. which candidate tests provided new evidence, and which promoted `C-nnnn` preserves each one;
4. which tests are duplicate, wrong, unstable, or ineligible for promotion;
5. which failures are implementation defects and which are test or documentation problems;
6. what the code needs fixed;
7. what the documentation/contract needs added;
8. which verifications passed, failed, blocked, or were inapplicable;
9. what uncertainty remains.

Give improvements in this order:

```text
first fix confirmed production defects
then fill in missing contracts
then strengthen tests and reverse verification for new behavior
finally delete duplicate tests or merge duplicate contracts
```

Do not automatically suggest keeping every candidate test just because test counts or coverage
grew.

## Auto-advance rules

Once the skill is invoked:

- automatically create the output directory and the isolated worktree;
- automatically launch the independent test subagent;
- automatically run compilation, target tests, full tests, applicable PIT, and reverse
  verification;
- automatically save intermediate artifacts and failure logs;
- automatically promote confirmed regressions and run the persistent suite's configured checks;
- check processes, logs, and output every 1–2 minutes during long tasks;
- report and handle per the one-retry rule when finding no CPU, no output, or obvious stalls;
- report results and open items once at the end.

Do not repeatedly confirm with the user over routine implementation choices, test corrections,
formatting, log archiving, or failure classification. Only these may pause for a decision:

- multiple reasonable directions for a public API shape or contract semantics;
- adding a dependency, changing the build baseline, releasing, merging main, or sending
  external messages;
- an external environment still blocked after the prescribed retries, with no meaningful
  evidence producible.

## Final artifacts

A successful run contains at least:

```text
${RUN_DIR}/
├── run-manifest.json
├── inputs/
├── logs/
├── contract.md
├── contract-gaps.md
├── candidates/
├── candidate-status.csv
├── promotions/
├── promotion-status.csv
├── reverse-verification/
├── verification-summary.md
└── report.md
```

If a step was not executed, the corresponding `SKIPPED`, `BLOCKED`, or `INCONCLUSIVE` status
and reason must be kept. The report lists raw status, disposition, whether the exception was
accepted, and the final gate status separately. Its final gate is `PASS` when every required
check passes without accepted exceptions, or `PASS WITH ACCEPTED EXCEPTIONS` when every other
required check passes and every exception is accepted; a raw `TIMED_OUT` must not be rewritten
as `KILLED`.
After writing the final report, finalize `run-manifest.json` with the run status, resolved
required gates and check results, accepted exceptions, command records, and final-artifact
hashes without changing frozen inputs or their hashes. Then run:

```bash
python3 ${SKILL_ROOT}/scripts/verify_run.py --run-dir ${RUN_DIR}
```

When verification fails, this round's report must not be marked reproducible or passing.

## Example invocation

```text
/contract-first-assurance
```

Once executed, the skill extracts the contract from the current PR diff, launches the isolated
test agent, verifies against the real implementation, completes deduplication and reverse
verification, and outputs contract gaps and code improvement suggestions.
