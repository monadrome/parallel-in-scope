# Review context, round 3 (lightweight): one fix commit

Range: 6bd5d76e .. e4dc3e01 on branch feat/join-policy-await-all. The commit removes `close()` from
the `TaskBatchResult` class Javadoc, simplifies `Par#allSucceeded` to treat a cancelled future
through `Futures.getDone` (catching `ExecutionException | CancellationException`), and adds
`AwaitAllJoinPolicyTest#mapAndCombineRunsTheCombineWhenEveryElementSucceeded`. Check that
`allSucceeded` is equivalent for done futures, that the new test fails if the all-success branch
of the AWAIT_ALL combine gate never submits, and the Javadoc wording. Do not read
`verification/contract-tests/`.
