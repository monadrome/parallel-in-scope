<!-- docs-version: 0.4.0 -->

# 5 分钟快速上手

在新的 Maven 项目中添加正式版依赖：

```xml
<dependency>
  <groupId>io.github.monadrome</groupId>
  <artifactId>parallel-in-scope</artifactId>
  <version>0.4.0</version>
</dependency>
```

构建运行时，计算四个值并读取聚合结果：

```java
ExecutorService pool = Executors.newFixedThreadPool(4);
ParRuntime runtime = ParRuntime.builder()
    .register(ParId.of("io"), pool)
    .defaultPar(ParId.of("io"))
    .build();
try {
    TaskBatchResult<Integer> result = runtime.defaultPar().map(
        Arrays.asList(1, 2, 3, 4), n -> n * n,
        BatchOptions.timeout("squares", Duration.ofSeconds(5)));
    System.out.println(result.valuesOrThrow());
} finally {
    runtime.close();
    pool.shutdownNow();
}
```

`map` 会阻塞调用线程。超时必须显式选择 `timeout(...)` 或 `inheritTimeout(...)`，批次在元素失败时快速失败。
由于 mapper 是 `Function`，受检异常必须包成 `UncheckedIOException` 或其他非受检异常。

固定的一组异构命名操作改用一次性任务组：每个成员声明自己的类型化 key、`Par` 和本次运行的
函数体（已在途的 future 改用 `adopt` 加入），可选的末端 combine 把成员值汇成一个结果。key 声明一次即可复用；`runAll()` 只执行一次
声明，阻塞到所有成员和 combine 终态后返回冻结结果。

```java
Par io = runtime.defaultPar();
TaskKey<User> userKey = TaskKey.named("user");
TaskKey<Account> accountKey = TaskKey.named("account");
TaskKey<Profile> profileKey = TaskKey.named("profile");

TaskGroupResult<Tuple2<User, Account>, Profile> profile = runtime
    .group("profile", Duration.ofSeconds(3))
    .par(userKey, io, () -> loadUser(userId))
    .par(accountKey, io, () -> loadAccount(userId))
    .combineValues(profileKey, io, values -> Profile.of(
        values.valueOf(userKey), values.valueOf(accountKey)))
    .runAll();
Profile value = profile.terminalValueOrThrow();
```

key 把声明与结果读取绑定到同一类型：`profile.resultOf(userKey)` 无需强制转换即可读到类型化的
`ImmediateResult<User>`。首个成员失败会取消其余成员，成员超时则取消整个组。

[交互式演示](https://monadrome.github.io/parallel-in-scope/interactive-demo.html) 的界面为中文，展示相同的取消、超时、快速失败和滑动窗口语义。
