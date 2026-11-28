三个候选全部晋升完成，四条命令均 exit 0。

**晋升结果**（写入 `/tmp/rso-promote/result.json`）
- `CONTRACT-TDD-004` (C-3) → `C-0005` `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0005AwaitQuiescenceValidatesArgsAndHonorsInterruptContractTest.java:1`
- `CONTRACT-TDD-008` (C-4) → `C-0006` `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0006InFlightZeroWhileExecutionStillRunningContractTest.java:1`
- `CONTRACT-TDD-010` (C-5) → `C-0007` `verification/contract-tests/src/test/java/io/github/monadrome/parallelinscope/C0007SnapshotUnexitedSignalContractTest.java:1`

**改动范围**：仅 package（改为 suite 包名）、移除同包冗余 import、类名、`public` → 包级私有（元数据正则要求行首 `class`）、新增 `import Tag` 与紧贴类上方的 `@Tag`；前置条件、操作序列、观察窗口、断言全部原样保留（已逐文件 diff 核对）。

**命令**（日志在 `/tmp/rso-promote/logs/01..04-*.{stdout,stderr}.log` + `.exit`）
- `regression-metadata` → 0
- `regression-format` → 0（palantir 对三个文件做了纯空白 reflow，已把最终字节重新同步到 `/tmp/rso-promote/adapted/`）
- `regression-tests` → 0：suite 7 个类各 1 个 testcase 全通过，core 3372 个测试全通过，`BUILD SUCCESS`
- `regression-execution` → 0

未提交；`git status` 仅显示 suite 内三个新增文件。注：`03-*.stderr.log` 中的堆栈来自 core 模块测试的预期异常日志（如 `LateFailureLoggingTest`），与 contract-tests 无关。