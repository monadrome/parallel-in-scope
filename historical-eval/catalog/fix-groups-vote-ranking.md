# Three-agent difficulty vote ranking

Total groups: **103**. Sorted from hard to easy.

| Rank | Group | Consensus | Votes (complexity/testability/impact) | Agreement | Priority | Representative | Subject |
|---:|---|---|---|---:|---|---|---|
| 1 | `abaf2bf6e921d7eb` | **hard** (majority) | hard/hard/hard | 3/3 | high | `02f103b1a5d7` | fix: publish cancellation intent atomically and pair the deadline scheduler with the token clock |
| 2 | `f5e7dcf141d5edba` | **hard** (majority) | hard/hard/hard | 3/3 | high | `9e20ef07081c` | fix: stop leaking parent-token listeners and close the purger after drain |
| 3 | `87fe33a4e82baf26` | **hard** (majority) | hard/hard/hard | 3/3 | high | `6aed79c80607` | fix: derive the default close grace from the remaining deadline |
| 4 | `ce13954cf22ab950` | **hard** (majority) | hard/hard/hard | 3/3 | high | `81fea3da9805` | fix: keep astronomical explicit timeouts below the no-deadline sentinel |
| 5 | `39f24f64069a65ea` | **hard** (majority) | hard/hard/hard | 3/3 | high | `d76b51571144` | fix: purge cancelled smart queue tasks heuristically |
| 6 | `b3cee164ff1dbd18` | **hard** (majority) | hard/hard/hard | 3/3 | high | `a3d043639309` | fix: absorb scheduler rejection when cancellation raced the deadline arming |
| 7 | `5a7f61cfcdc93d0a` | **hard** (majority) | hard/hard/hard | 3/3 | high | `3c447735a09f` | fix: close cancellation and timer lifecycle gaps |
| 8 | `8a81f53dd80b57ce` | **hard** (majority) | hard/hard/hard | 3/3 | high | `95b61f7a0d7d` | fix!: bind the batch token before submitting, and isolate borrowed threads |
| 9 | `cabd29a36eb33ec9` | **hard** (majority) | hard/hard/hard | 3/3 | high | `8245750ea4bf` | fix: make failure inspection interrupt-oblivious and freeze snapshots in one pass |
| 10 | `f1ed894a1c3164b2` | **hard** (majority) | hard/hard/hard | 3/3 | high | `a07861df9a33` | fix: finish Error Prone cleanup batch |
| 11 | `af5db9ca653cb5b7` | **hard** (majority) | hard/hard/hard | 3/3 | high | `beefc67d5d63` | fix: read executor starvation from queue admission, not the thread bound |
| 12 | `2ca1eed60dfa8a20` | **hard** (majority) | hard/hard/hard | 3/3 | high | `5da9e097daa5` | fix: harden thread-pool starvation observation |
| 13 | `b7f8f5bb5b00a3f6` | **hard** (majority) | hard/hard/hard | 3/3 | medium | `6903ff1bf596` | fix: isolate tasktype diagnostics from admission |
| 14 | `b0d9c3de52190bfa` | **hard** (majority) | hard/hard/hard | 3/3 | medium | `83fedb6e1f64` | fix: saturate every remaining-time computation against the monotonic clock |
| 15 | `af46f562f3bb5a18` | **hard** (majority) | hard/hard/hard | 3/3 | medium | `435b43b17505` | fix: resolve the five verified 0.2.0 defects from the defect analysis |
| 16 | `f850d5d48004d532` | **hard** (majority) | hard/hard/hard | 3/3 | medium | `2e39c0290494` | fix: decide tasktype mismatches against one state |
| 17 | `ca18abd37ac16277` | **hard** (majority) | hard/hard/hard | 3/3 | medium | `955b56abc263` | fix: implement confirmed proposal defects |
| 18 | `47fa38680bad250f` | **hard** (majority) | hard/hard/hard | 3/3 | low | `4402a0eb8bb4` | fix: align annotation contracts with runtime behavior |
| 19 | `3d08102f362c5327` | **hard** (majority) | hard/hard/medium | 2/3 | high | `5d2eea6026fc` | fix(scope): read done futures without interruption and guard the unbounded deadline |
| 20 | `04ec938a8657becd` | **hard** (majority) | hard/hard/medium | 2/3 | high | `3d28080c399d` | fix: read failed tasks without regard to the caller's interrupt flag |
| 21 | `ed406100dea6e826` | **hard** (majority) | hard/hard/medium | 2/3 | high | `c0b0158bf4fc` | fix: read failed tasks without regard to the caller's interrupt flag |
| 22 | `2ec8d30951add0a0` | **hard** (majority) | hard/hard/medium | 2/3 | high | `7ef83d85c2c1` | fix: stop the default options from disabling SmartBlockingQueue |
| 23 | `579f8c60343f90aa` | **hard** (majority) | hard/medium/hard | 2/3 | high | `968e291c3ef6` | fix: make completion cancellation visible to purge (#33) |
| 24 | `63628ec430a1bd3e` | **hard** (majority) | hard/hard/medium | 2/3 | high | `e61db895635a` | fix: stop the default options from disabling SmartBlockingQueue |
| 25 | `d465fbcb6d2ee35b` | **hard** (majority) | hard/medium/hard | 2/3 | high | `c0027aa91738` | fix: make completion cancellation visible to purge |
| 26 | `37c02cbbc0462572` | **hard** (majority) | hard/medium/hard | 2/3 | high | `d68eeab6ba85` | fix: propagate cancel(false) interrupt intent down the token tree |
| 27 | `4266f11e628c0da1` | **hard** (majority) | medium/hard/hard | 2/3 | high | `bf1fe01d46d8` | fix: keep the deferred combine edge out of the executor deadlock projection |
| 28 | `57d42f765ef86a49` | **hard** (majority) | medium/hard/hard | 2/3 | high | `084faba23d36` | fix: refresh task graph snapshots after new edges |
| 29 | `d37f5d163c26f8f5` | **hard** (majority) | medium/hard/hard | 2/3 | high | `da28755765d6` | fix: never lose CANCEL_REQUESTED_RUNNING under cancel/run race |
| 30 | `9360a527cc66d0db` | **hard** (majority) | medium/hard/hard | 2/3 | high | `b300efc01775` | fix: keep a borrowed thread's SubmissionScope out of the task that borrows it |
| 31 | `ded3a7996d24e86b` | **hard** (majority) | medium/hard/hard | 2/3 | high | `a3ec7c2215b4` | fix: keep a borrowed thread's SubmissionScope out of the task that borrows it |
| 32 | `e1196cc87bff90de` | **hard** (majority) | medium/hard/hard | 2/3 | high | `3ede3cd4c1a7` | fix: keep the settle pin reachable when an observation snapshot fails |
| 33 | `91a9091b9d764358` | **hard** (majority) | easy/hard/hard | 2/3 | high | `835ccf1c2055` | fix: report zero timing for never-started snapshots on negative clocks |
| 34 | `f760891e396c2747` | **hard** (majority) | easy/hard/hard | 2/3 | high | `4792097d5c15` | fix: close group startup admission before scheduler retirement |
| 35 | `80f659bb3392b580` | **hard** (majority) | hard/hard/medium | 2/3 | medium | `3bdb65070fbf` | fix: allow draining shutdown recovery elements |
| 36 | `c46c383ed48d281a` | **hard** (majority) | medium/hard/hard | 2/3 | medium | `a574c150adf2` | fix: run adopted closers on a runtime thread and settle abandoned mirrors |
| 37 | `42b67c4fdc138475` | **hard** (majority) | medium/hard/hard | 2/3 | medium | `f90795df4e7e` | fix: refuse a combine its pool would run on the convergence thread |
| 38 | `679508df3792a90b` | **hard** (majority) | medium/hard/hard | 2/3 | medium | `e218b2f5e219` | fix: refuse a combine its pool would run on the convergence thread |
| 39 | `6ecfadd025e19500` | **hard** (majority) | medium/hard/hard | 2/3 | medium | `5678d512d543` | fix: refuse a combine its pool would run on the convergence thread |
| 40 | `0d3e1522c35941bb` | **medium** (majority) | medium/medium/medium | 3/3 | high | `231a760eb23f` | fix: normalize synchronous cancellation results |
| 41 | `039a522618281467` | **medium** (majority) | medium/medium/medium | 3/3 | high | `8d8be45e6f1b` | fix: rawCheckpoint restores the interrupt flag instead of consuming it |
| 42 | `11876918bbb70943` | **medium** (majority) | medium/medium/medium | 3/3 | high | `58ec4164af63` | fix: close CancellationToken construction-window and logging-failure gaps |
| 43 | `132598ab20a88673` | **medium** (majority) | medium/medium/medium | 3/3 | high | `7266ed79a0ca` | fix: abandon incomplete futures when submission bails mid-batch |
| 44 | `711067b9f92ec1ee` | **medium** (majority) | medium/medium/medium | 3/3 | high | `b40d11a0345f` | fix: avoid group allocations for known executor waits |
| 45 | `76a7451e28b28b63` | **medium** (majority) | medium/medium/medium | 3/3 | high | `661d2ebd2f23` | fix(queue): order peek behind count and reject offers above capacity |
| 46 | `76c2cd22be0c3502` | **medium** (majority) | medium/medium/medium | 3/3 | high | `8bed65c72dd5` | fix: propagate observation to top-level batches |
| 47 | `88ff519ce40d9405` | **medium** (majority) | medium/medium/medium | 3/3 | high | `cc1f5e00f4e2` | fix: document refused releases and pin the deadline-path closer thread |
| 48 | `b96a31dd25653b4a` | **medium** (majority) | medium/medium/medium | 3/3 | high | `2a04e9bd2bfe` | fix: let an interrupt in the observation barrier stay an interrupt |
| 49 | `c56b7d6dc2cce151` | **medium** (majority) | medium/medium/medium | 3/3 | high | `e5ef0890ecaf` | fix: check interruption before quiescence state in awaitQuiescence |
| 50 | `d4bd79825dfde6cc` | **medium** (majority) | medium/medium/medium | 3/3 | high | `7f703100bf66` | fix: coordinate GlobalPar shutdown with batch admission |
| 51 | `faacda4d5580c3a1` | **medium** (majority) | medium/medium/medium | 3/3 | high | `24ef0b0c2b83` | fix: classify cancellation-token transitions by event source, not exception type |
| 52 | `fe71ccdb35918889` | **medium** (majority) | medium/medium/medium | 3/3 | high | `d9239f331fc6` | fix: complete placeholders after sliding-window cancellation |
| 53 | `ff4efcf30212f8c6` | **medium** (majority) | medium/medium/medium | 3/3 | high | `8a922010fdf0` | fix: clean cancelled timers and preserve batch failures |
| 54 | `2f5e47b75f0e8bfb` | **medium** (majority) | medium/medium/medium | 3/3 | high | `796ec8113292` | fix: attribute deadline expiry consistently between a group and its members |
| 55 | `4e46475c8160f14c` | **medium** (majority) | medium/medium/medium | 3/3 | high | `95162179f621` | fix: warn for an inert CPU_BOUND combine and correct the enqueue docs |
| 56 | `923914ef3d9560cb` | **medium** (majority) | medium/medium/medium | 3/3 | high | `a5c2d823fad1` | fix: saturate duration conversion for deadline budgets |
| 57 | `a3bebbc583b0b865` | **medium** (majority) | medium/medium/medium | 3/3 | high | `9d7f856c7aad` | fix: scope the one-attribution rule precisely and drop an ineffective batch check |
| 58 | `b995b22fd2489f18` | **medium** (majority) | medium/medium/medium | 3/3 | high | `0a1fc52ce34d` | fix: make batch awaitBodyCompletion await element future settlement too |
| 59 | `698d80b3f5cd0ec3` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `38c1b926eb24` | fix: record graph edges in the scope a unit resolved to, not the ambient one |
| 60 | `85d3a5f8f664d0f0` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `827d2b9cd99e` | fix: resolve the observation scope from the thread first, and propagate it |
| 61 | `65ef2b1f7abbd400` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `0867df54a7e4` | fix: publish the graph report before invoking replaceable JUL handlers |
| 62 | `71f502afee6d9176` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `335cc28872ec` | fix: publish failed graph report before diagnostic logging on detection failure |
| 63 | `a1b59212d7b2ed5c` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `f3a6ef577c81` | fix: publish observation report before issue diagnostics |
| 64 | `a6de3c1382d43b93` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `5e5fa6ca7fe7` | fix: make a closed observation scope transparent to its open outer scope |
| 65 | `d3d37338f3bd8212` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `5f7554991834` | fix: retain adopting runs until discarded values are handed to closers |
| 66 | `2aee0be67d80a5e9` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `2d4c23e49cf7` | fix: give every task one attribution and keep only the active view in contracts |
| 67 | `8f5f68ec64341e4a` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `93966cfdc869` | fix: isolate GlobalPar runtime dispatch |
| 68 | `e32b7b5a6153ed31` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `b6d84198a2c4` | fix: publish inline CPU fallback completions |
| 69 | `e9d0398c83a4c3fd` | **medium** (majority) | medium/medium/medium | 3/3 | medium | `c135f9f73cdb` | fix: count a task on the barrier even when its completion callback throws |
| 70 | `6f91f009378cd14b` | **medium** (majority) | hard/medium/medium | 2/3 | high | `0ee55889a7ac` | fix: attribute deadline expiry consistently between a group and its members |
| 71 | `cc88ce4bc5ad83d2` | **medium** (majority) | hard/medium/medium | 2/3 | high | `17b0a5b01069` | fix: attribute deadline expiry consistently between a group and its members |
| 72 | `f8a99db5ac4ce689` | **medium** (majority) | hard/medium/medium | 2/3 | high | `738225132b2f` | fix: coordinate cancellation interrupt delivery with runner exit |
| 73 | `516e367cdecfdd9f` | **medium** (majority) | medium/medium/hard | 2/3 | high | `62473884c2de` | fix: fail batch futures when the executor handoff throws an Error |
| 74 | `b27c2dfc4219034c` | **medium** (majority) | medium/hard/medium | 2/3 | high | `275cea1d5cb2` | fix!: harden queue draining and capacity transitions |
| 75 | `baa08bb55045760c` | **medium** (majority) | medium/hard/medium | 2/3 | high | `b81a7427e5a1` | fix: freeze each batch element as attributed when it settled |
| 76 | `34c8370fa5317123` | **medium** (majority) | medium/hard/medium | 2/3 | high | `7423c0a4d192` | fix(queue): complete draining queue mutation semantics |
| 77 | `8dcd5c89cbd40e24` | **medium** (majority) | medium/medium/hard | 2/3 | high | `50644d4eaf99` | fix: isolate terminal publication from handoff-Error logging failure |
| 78 | `9b52bb81f66c218d` | **medium** (majority) | medium/hard/medium | 2/3 | high | `f999c17c25ee` | fix: report an inert CPU_BOUND like an inert rejectEnqueue |
| 79 | `a77e5de2c3ba6ba1` | **medium** (majority) | medium/medium/hard | 2/3 | high | `aaded36c92de` | fix: settle unified submission-failure semantics for executor handoff failures |
| 80 | `ef2e055b1d971397` | **medium** (majority) | medium/medium/hard | 2/3 | high | `45fad1278978` | fix: coalesce concurrent executor purges |
| 81 | `809026382b84587c` | **medium** (majority) | medium/medium/hard | 2/3 | high | `b7538d80be8c` | fix: sever parent cancellation link when child token reaches terminal state |
| 82 | `8d90217630b27b5d` | **medium** (majority) | medium/hard/medium | 2/3 | high | `763afca5880c` | fix(scope)!: derive group task names from keys |
| 83 | `d182677347a6307c` | **medium** (majority) | medium/medium/hard | 2/3 | high | `eef081dc49b0` | fix: stop repeated purger retries after failure |
| 84 | `f792ac01a7aa1047` | **medium** (majority) | medium/medium/hard | 2/3 | high | `3ed1ac0bde50` | fix: skip executor handoff for already-terminal tasks in the sliding window |
| 85 | `55840e22cfd76c8a` | **medium** (majority) | medium/hard/medium | 2/3 | high | `46b39364397d` | fix: correct stale cancelOn javadoc links and restore loser-cascade tests |
| 86 | `03bd5b4c255a8735` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `7b686cae25fb` | fix: guard the sliding-window claim range and close K3 review items |
| 87 | `3ee94de4848a05c6` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `fa6a0f28d822` | fix: keep the issue diagnostic when a report listener throws an error |
| 88 | `6259ca47b8c253e4` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `ab5936a1035a` | fix: decide executor cycles on executor identity and record no root executor edge |
| 89 | `c3f75364f915a2be` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `5d9139278466` | fix: saturate negative duration overflow to the elapsed end |
| 90 | `0c3b5b5b76a5c660` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `dd0211292127` | fix: read executor blocking risk from the pool's structure |
| 91 | `c2423c1b1700f05b` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `96ce991030f0` | fix: keep screening failures out of task results |
| 92 | `b35b53d073a70a46` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `17866d31dcac` | fix: read executor capability facts through a TTL wrapper |
| 93 | `c682b901e5e2b370` | **medium** (majority) | medium/medium/easy | 2/3 | medium | `7fe522f9bac8` | fix: isolate ISSUE diagnostics from report publication |
| 94 | `f899ec220732074c` | **medium** (majority) | medium/hard/medium | 2/3 | medium | `8e9e4380de9a` | fix: isolate adopted source listener failures and settle mirrors from one listener |
| 95 | `105f6b30c2d01c0a` | **medium** (majority) | easy/medium/medium | 2/3 | medium | `ff0e10bfb5d1` | fix: stop a foreign unit from laundering its parent's observation scope |
| 96 | `4400b898c7b96e38` | **easy** (majority) | easy/easy/easy | 3/3 | medium | `0c268f327a04` | fix: guard screening bookkeeping and document dropped signals |
| 97 | `f7b4b4a609bde766` | **easy** (majority) | easy/easy/easy | 3/3 | medium | `9c5147da7501` | fix: reject installing a closed ParRuntime into the global slot |
| 98 | `020cfc94c90abbe0` | **easy** (majority) | easy/easy/easy | 3/3 | low | `6ec87f7dedcc` | fix: annotate CombineBody result as @Nullable for NullAway consumers |
| 99 | `95075160213eea60` | **easy** (majority) | easy/easy/easy | 3/3 | low | `ac23de7348d9` | fix: replace busy waits flagged by error prone |
| 100 | `4bba140a73070ed3` | **easy** (majority) | easy/easy/medium | 2/3 | high | `f1c825701519` | fix(group)!: reject a member that reuses the combine's name |
| 101 | `b6a6ee8baf9fe544` | **easy** (majority) | easy/easy/medium | 2/3 | high | `b6eb47d5129b` | fix: release a group's prepared work when startup fails after preparation |
| 102 | `184324251a497713` | **easy** (majority) | easy/medium/easy | 2/3 | low | `14da4b2d27f0` | fix: add direct executor diagnostic name |
| 103 | `3ae226b6d20fb951` | **easy** (majority) | easy/medium/easy | 2/3 | low | `5ceb4e123864` | fix: complete direct executor API contract |

## Consensus counts

- `hard`: 39
- `medium`: 56
- `easy`: 8

`majority` means at least two votes agree. `median-no-majority` is used when all three differ.
