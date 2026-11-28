# Assurance promotion (fresh context)

You own the promotion phase of a contract-first assurance run. The implementer of the change does not
read the hidden suite, so you do this work and report back only paths, IDs, and command results.

Repository checkout (detached, yours to edit): /Users/linqh/IdeaProjects/parallel-in-scope-new/.worktrees/rso-assurance-promote
Hidden regression suite root: verification/contract-tests/ in that checkout. Read
verification/contract-tests/AGENTS.md first and follow its module conventions exactly.

Eligible sealed candidates (in /tmp/rso-promote/candidates/), each already classified `new coverage`,
passed twice on the real implementation, and reverse-verified:

| candidate_id | source_contract_id | file |
|---|---|---|
| CONTRACT-TDD-004 | C-3 | ContractTdd004AwaitQuiescenceValidatesArgsAndHonorsInterruptTest.java |
| CONTRACT-TDD-008 | C-4 | ContractTdd008InFlightZeroWhileExecutionStillRunningTest.java |
| CONTRACT-TDD-010 | C-5 | ContractTdd010SnapshotUnexitedSignalTest.java |

For each candidate, in order:

1. Allocate the next unused four-digit contract ID in the suite (`C-nnnn`), unique across all
   existing classes and across the three you add.
2. Adapt only package, class name (`Cnnnn<Subject>ContractTest`), the `@Tag("C-nnnn")` placement, and
   build integration. Preserve the precondition state, operation sequence, observation window, and
   every assertion. Do not add, remove, weaken, or strengthen assertions.
3. Write the adapted class into the suite, and also copy the exact adapted file to
   /tmp/rso-promote/adapted/<same file name>.

Then run, from the checkout root, with JAVA_HOME set to the JDK 25 home
(`/usr/libexec/java_home -v 25`) and Maven offline (`mvn -o`):

```bash
python3 scripts/check-contract-tests.py
mvn -o -Pcontract-tests -pl verification/contract-tests spotless:apply
mvn -o -Pcontract-tests -pl verification/contract-tests -am clean test
python3 scripts/check-contract-tests.py --reports verification/contract-tests/target/surefire-reports
```

If `spotless:apply` reformats an adapted file, recopy the final bytes to /tmp/rso-promote/adapted/.
Save each command's stdout and stderr under /tmp/rso-promote/logs/ as NN-<name>.stdout.log and
NN-<name>.stderr.log, and its exit code in NN-<name>.exit.

If a candidate cannot be promoted (its tests fail in the suite, or an ID or convention cannot be
satisfied), remove its new file from the suite so the checkout holds no partial promotion, and mark it
blocked with the reason.

Finally write /tmp/rso-promote/result.json:

```json
{"promotions": [{"candidate_id": "CONTRACT-TDD-004", "source_contract_id": "C-3", "contract_id": "C-nnnn",
  "target_path": "verification/contract-tests/src/test/java/.../Cnnnn...ContractTest.java",
  "adapted_file": "/tmp/rso-promote/adapted/Cnnnn...ContractTest.java", "status": "promoted|blocked",
  "reason": "one sentence"}],
 "commands": [{"name": "regression-metadata", "argv": ["python3", "scripts/check-contract-tests.py"], "exit": 0}, ...]}
```

Use the names regression-metadata, regression-format, regression-tests, regression-execution for the
four commands. Do not commit, do not touch files outside the suite root and /tmp/rso-promote/, and do
not edit production code or other tests. Stop after writing result.json.
