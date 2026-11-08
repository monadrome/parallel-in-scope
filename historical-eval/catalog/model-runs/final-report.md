# Ranked model evaluation

- Total groups: **103**
- Agent/model: `codex` + `gpt-5.6-terra`
- Reasoning effort: `medium`
- Eligible historical oracles: **88**
- Model pass rate on eligible groups: **9.09%**

## Status counts

| Status | Count |
|---|---:|
| `model-fail` | 80 |
| `model-pass` | 8 |
| `not-eligible` | 15 |

## Difficulty breakdown

| Difficulty | Total | Eligible | Pass | Fail | Not eligible |
|---|---:|---:|---:|---:|---:|
| hard | 39 | 33 | 2 | 31 | 6 |
| medium | 56 | 50 | 5 | 45 | 6 |
| easy | 8 | 5 | 1 | 4 | 3 |

## Ranked groups

| Rank | Difficulty | Group | Historical | Model | Subject |
|---:|---|---|---|---|---|
| 1 | **hard** | `abaf2bf6e921d7eb` | `verified` | `model-fail` | fix: publish cancellation intent atomically and pair the deadline scheduler with the token clock |
| 2 | **hard** | `f5e7dcf141d5edba` | `verified` | `model-fail` | fix: stop leaking parent-token listeners and close the purger after drain |
| 3 | **hard** | `87fe33a4e82baf26` | `verified` | `model-fail` | fix: derive the default close grace from the remaining deadline |
| 4 | **hard** | `ce13954cf22ab950` | `verified` | `model-fail` | fix: keep astronomical explicit timeouts below the no-deadline sentinel |
| 5 | **hard** | `39f24f64069a65ea` | `verified` | `model-fail` | fix: purge cancelled smart queue tasks heuristically |
| 6 | **hard** | `b3cee164ff1dbd18` | `verified` | `model-fail` | fix: absorb scheduler rejection when cancellation raced the deadline arming |
| 7 | **hard** | `5a7f61cfcdc93d0a` | `verified` | `model-fail` | fix: close cancellation and timer lifecycle gaps |
| 8 | **hard** | `8a81f53dd80b57ce` | `verified` | `model-fail` | fix!: bind the batch token before submitting, and isolate borrowed threads |
| 9 | **hard** | `cabd29a36eb33ec9` | `verified` | `model-fail` | fix: make failure inspection interrupt-oblivious and freeze snapshots in one pass |
| 10 | **hard** | `f1ed894a1c3164b2` | `baseline-pass` | `not-eligible` | fix: finish Error Prone cleanup batch |
| 11 | **hard** | `af5db9ca653cb5b7` | `verified` | `model-fail` | fix: read executor starvation from queue admission, not the thread bound |
| 12 | **hard** | `2ca1eed60dfa8a20` | `verified` | `model-fail` | fix: harden thread-pool starvation observation |
| 13 | **hard** | `b7f8f5bb5b00a3f6` | `verified` | `model-fail` | fix: isolate tasktype diagnostics from admission |
| 14 | **hard** | `b0d9c3de52190bfa` | `verified` | `model-fail` | fix: saturate every remaining-time computation against the monotonic clock |
| 15 | **hard** | `af46f562f3bb5a18` | `verified` | `model-fail` | fix: resolve the five verified 0.2.0 defects from the defect analysis |
| 16 | **hard** | `f850d5d48004d532` | `verified` | `model-fail` | fix: decide tasktype mismatches against one state |
| 17 | **hard** | `ca18abd37ac16277` | `verified` | `model-fail` | fix: implement confirmed proposal defects |
| 18 | **hard** | `47fa38680bad250f` | `baseline-pass` | `not-eligible` | fix: align annotation contracts with runtime behavior |
| 19 | **hard** | `3d08102f362c5327` | `baseline-pass` | `not-eligible` | fix(scope): read done futures without interruption and guard the unbounded deadline |
| 20 | **hard** | `04ec938a8657becd` | `verified` | `model-fail` | fix: read failed tasks without regard to the caller's interrupt flag |
| 21 | **hard** | `ed406100dea6e826` | `verified` | `model-fail` | fix: read failed tasks without regard to the caller's interrupt flag |
| 22 | **hard** | `2ec8d30951add0a0` | `verified` | `model-fail` | fix: stop the default options from disabling SmartBlockingQueue |
| 23 | **hard** | `579f8c60343f90aa` | `verified` | `model-fail` | fix: make completion cancellation visible to purge (#33) |
| 24 | **hard** | `63628ec430a1bd3e` | `verified` | `model-fail` | fix: stop the default options from disabling SmartBlockingQueue |
| 25 | **hard** | `d465fbcb6d2ee35b` | `verified` | `model-fail` | fix: make completion cancellation visible to purge |
| 26 | **hard** | `37c02cbbc0462572` | `verified` | `model-fail` | fix: propagate cancel(false) interrupt intent down the token tree |
| 27 | **hard** | `4266f11e628c0da1` | `verified` | `model-fail` | fix: keep the deferred combine edge out of the executor deadlock projection |
| 28 | **hard** | `57d42f765ef86a49` | `verified` | `model-fail` | fix: refresh task graph snapshots after new edges |
| 29 | **hard** | `d37f5d163c26f8f5` | `verified` | `model-pass` | fix: never lose CANCEL_REQUESTED_RUNNING under cancel/run race |
| 30 | **hard** | `9360a527cc66d0db` | `fixed-fail` | `not-eligible` | fix: keep a borrowed thread's SubmissionScope out of the task that borrows it |
| 31 | **hard** | `ded3a7996d24e86b` | `verified` | `model-fail` | fix: keep a borrowed thread's SubmissionScope out of the task that borrows it |
| 32 | **hard** | `e1196cc87bff90de` | `verified` | `model-fail` | fix: keep the settle pin reachable when an observation snapshot fails |
| 33 | **hard** | `91a9091b9d764358` | `verified` | `model-pass` | fix: report zero timing for never-started snapshots on negative clocks |
| 34 | **hard** | `f760891e396c2747` | `baseline-pass` | `not-eligible` | fix: close group startup admission before scheduler retirement |
| 35 | **hard** | `80f659bb3392b580` | `verified` | `model-fail` | fix: allow draining shutdown recovery elements |
| 36 | **hard** | `c46c383ed48d281a` | `verified` | `model-fail` | fix: run adopted closers on a runtime thread and settle abandoned mirrors |
| 37 | **hard** | `42b67c4fdc138475` | `fixed-fail` | `not-eligible` | fix: refuse a combine its pool would run on the convergence thread |
| 38 | **hard** | `679508df3792a90b` | `verified` | `model-fail` | fix: refuse a combine its pool would run on the convergence thread |
| 39 | **hard** | `6ecfadd025e19500` | `verified` | `model-fail` | fix: refuse a combine its pool would run on the convergence thread |
| 40 | **medium** | `0d3e1522c35941bb` | `verified` | `model-fail` | fix: normalize synchronous cancellation results |
| 41 | **medium** | `039a522618281467` | `verified` | `model-fail` | fix: rawCheckpoint restores the interrupt flag instead of consuming it |
| 42 | **medium** | `11876918bbb70943` | `verified` | `model-fail` | fix: close CancellationToken construction-window and logging-failure gaps |
| 43 | **medium** | `132598ab20a88673` | `verified` | `model-fail` | fix: abandon incomplete futures when submission bails mid-batch |
| 44 | **medium** | `711067b9f92ec1ee` | `verified` | `model-fail` | fix: avoid group allocations for known executor waits |
| 45 | **medium** | `76a7451e28b28b63` | `verified` | `model-pass` | fix(queue): order peek behind count and reject offers above capacity |
| 46 | **medium** | `76c2cd22be0c3502` | `verified` | `model-fail` | fix: propagate observation to top-level batches |
| 47 | **medium** | `88ff519ce40d9405` | `baseline-pass` | `not-eligible` | fix: document refused releases and pin the deadline-path closer thread |
| 48 | **medium** | `b96a31dd25653b4a` | `verified` | `model-fail` | fix: let an interrupt in the observation barrier stay an interrupt |
| 49 | **medium** | `c56b7d6dc2cce151` | `verified` | `model-fail` | fix: check interruption before quiescence state in awaitQuiescence |
| 50 | **medium** | `d4bd79825dfde6cc` | `verified` | `model-fail` | fix: coordinate GlobalPar shutdown with batch admission |
| 51 | **medium** | `faacda4d5580c3a1` | `verified` | `model-fail` | fix: classify cancellation-token transitions by event source, not exception type |
| 52 | **medium** | `fe71ccdb35918889` | `verified` | `model-pass` | fix: complete placeholders after sliding-window cancellation |
| 53 | **medium** | `ff4efcf30212f8c6` | `verified` | `model-fail` | fix: clean cancelled timers and preserve batch failures |
| 54 | **medium** | `2f5e47b75f0e8bfb` | `verified` | `model-fail` | fix: attribute deadline expiry consistently between a group and its members |
| 55 | **medium** | `4e46475c8160f14c` | `verified` | `model-fail` | fix: warn for an inert CPU_BOUND combine and correct the enqueue docs |
| 56 | **medium** | `923914ef3d9560cb` | `verified` | `model-fail` | fix: saturate duration conversion for deadline budgets |
| 57 | **medium** | `a3bebbc583b0b865` | `baseline-pass` | `not-eligible` | fix: scope the one-attribution rule precisely and drop an ineffective batch check |
| 58 | **medium** | `b995b22fd2489f18` | `verified` | `model-fail` | fix: make batch awaitBodyCompletion await element future settlement too |
| 59 | **medium** | `698d80b3f5cd0ec3` | `verified` | `model-pass` | fix: record graph edges in the scope a unit resolved to, not the ambient one |
| 60 | **medium** | `85d3a5f8f664d0f0` | `verified` | `model-fail` | fix: resolve the observation scope from the thread first, and propagate it |
| 61 | **medium** | `65ef2b1f7abbd400` | `verified` | `model-fail` | fix: publish the graph report before invoking replaceable JUL handlers |
| 62 | **medium** | `71f502afee6d9176` | `verified` | `model-pass` | fix: publish failed graph report before diagnostic logging on detection failure |
| 63 | **medium** | `a1b59212d7b2ed5c` | `verified` | `model-fail` | fix: publish observation report before issue diagnostics |
| 64 | **medium** | `a6de3c1382d43b93` | `verified` | `model-fail` | fix: make a closed observation scope transparent to its open outer scope |
| 65 | **medium** | `d3d37338f3bd8212` | `verified` | `model-fail` | fix: retain adopting runs until discarded values are handed to closers |
| 66 | **medium** | `2aee0be67d80a5e9` | `baseline-pass` | `not-eligible` | fix: give every task one attribution and keep only the active view in contracts |
| 67 | **medium** | `8f5f68ec64341e4a` | `verified` | `model-fail` | fix: isolate GlobalPar runtime dispatch |
| 68 | **medium** | `e32b7b5a6153ed31` | `verified` | `model-fail` | fix: publish inline CPU fallback completions |
| 69 | **medium** | `e9d0398c83a4c3fd` | `verified` | `model-fail` | fix: count a task on the barrier even when its completion callback throws |
| 70 | **medium** | `6f91f009378cd14b` | `verified` | `model-fail` | fix: attribute deadline expiry consistently between a group and its members |
| 71 | **medium** | `cc88ce4bc5ad83d2` | `verified` | `model-fail` | fix: attribute deadline expiry consistently between a group and its members |
| 72 | **medium** | `f8a99db5ac4ce689` | `verified` | `model-fail` | fix: coordinate cancellation interrupt delivery with runner exit |
| 73 | **medium** | `516e367cdecfdd9f` | `verified` | `model-fail` | fix: fail batch futures when the executor handoff throws an Error |
| 74 | **medium** | `b27c2dfc4219034c` | `baseline-pass` | `not-eligible` | fix!: harden queue draining and capacity transitions |
| 75 | **medium** | `baa08bb55045760c` | `verified` | `model-fail` | fix: freeze each batch element as attributed when it settled |
| 76 | **medium** | `34c8370fa5317123` | `baseline-pass` | `not-eligible` | fix(queue): complete draining queue mutation semantics |
| 77 | **medium** | `8dcd5c89cbd40e24` | `verified` | `model-fail` | fix: isolate terminal publication from handoff-Error logging failure |
| 78 | **medium** | `9b52bb81f66c218d` | `verified` | `model-fail` | fix: report an inert CPU_BOUND like an inert rejectEnqueue |
| 79 | **medium** | `a77e5de2c3ba6ba1` | `verified` | `model-fail` | fix: settle unified submission-failure semantics for executor handoff failures |
| 80 | **medium** | `ef2e055b1d971397` | `verified` | `model-fail` | fix: coalesce concurrent executor purges |
| 81 | **medium** | `809026382b84587c` | `verified` | `model-fail` | fix: sever parent cancellation link when child token reaches terminal state |
| 82 | **medium** | `8d90217630b27b5d` | `verified` | `model-fail` | fix(scope)!: derive group task names from keys |
| 83 | **medium** | `d182677347a6307c` | `verified` | `model-fail` | fix: stop repeated purger retries after failure |
| 84 | **medium** | `f792ac01a7aa1047` | `verified` | `model-fail` | fix: skip executor handoff for already-terminal tasks in the sliding window |
| 85 | **medium** | `55840e22cfd76c8a` | `fixed-fail` | `not-eligible` | fix: correct stale cancelOn javadoc links and restore loser-cascade tests |
| 86 | **medium** | `03bd5b4c255a8735` | `verified` | `model-fail` | fix: guard the sliding-window claim range and close K3 review items |
| 87 | **medium** | `3ee94de4848a05c6` | `verified` | `model-fail` | fix: keep the issue diagnostic when a report listener throws an error |
| 88 | **medium** | `6259ca47b8c253e4` | `verified` | `model-fail` | fix: decide executor cycles on executor identity and record no root executor edge |
| 89 | **medium** | `c3f75364f915a2be` | `verified` | `model-pass` | fix: saturate negative duration overflow to the elapsed end |
| 90 | **medium** | `0c3b5b5b76a5c660` | `verified` | `model-fail` | fix: read executor blocking risk from the pool's structure |
| 91 | **medium** | `c2423c1b1700f05b` | `verified` | `model-fail` | fix: keep screening failures out of task results |
| 92 | **medium** | `b35b53d073a70a46` | `verified` | `model-fail` | fix: read executor capability facts through a TTL wrapper |
| 93 | **medium** | `c682b901e5e2b370` | `verified` | `model-fail` | fix: isolate ISSUE diagnostics from report publication |
| 94 | **medium** | `f899ec220732074c` | `verified` | `model-fail` | fix: isolate adopted source listener failures and settle mirrors from one listener |
| 95 | **medium** | `105f6b30c2d01c0a` | `verified` | `model-fail` | fix: stop a foreign unit from laundering its parent's observation scope |
| 96 | **easy** | `4400b898c7b96e38` | `baseline-pass` | `not-eligible` | fix: guard screening bookkeeping and document dropped signals |
| 97 | **easy** | `f7b4b4a609bde766` | `verified` | `model-fail` | fix: reject installing a closed ParRuntime into the global slot |
| 98 | **easy** | `020cfc94c90abbe0` | `verified` | `model-fail` | fix: annotate CombineBody result as @Nullable for NullAway consumers |
| 99 | **easy** | `95075160213eea60` | `baseline-pass` | `not-eligible` | fix: replace busy waits flagged by error prone |
| 100 | **easy** | `4bba140a73070ed3` | `verified` | `model-pass` | fix(group)!: reject a member that reuses the combine's name |
| 101 | **easy** | `b6a6ee8baf9fe544` | `verified` | `model-fail` | fix: release a group's prepared work when startup fails after preparation |
| 102 | **easy** | `184324251a497713` | `verified` | `model-fail` | fix: add direct executor diagnostic name |
| 103 | **easy** | `3ae226b6d20fb951` | `baseline-pass` | `not-eligible` | fix: complete direct executor API contract |
