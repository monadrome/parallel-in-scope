# 批量调用最佳实践：HTTP/DB/RPC 完整方案


批量调用是后端开发中最常见的并发场景。两个最典型场景的完整问题导向分析（含原生写法复现）见
[G5. 批量 HTTP 调用](G5-batch-http-calls.md)（并发控制 + 超时 + fail-fast）与
[G6. 数据库批量查询](G6-batch-db-query.md)（分片并行）。本文补充一个混合 IO 场景，
并汇总跨场景通用的模式与反模式。

---

## 场景：混合 IO 调用

一个请求需要同时调 HTTP、查 DB、读缓存，三种 IO 混在一个批次里：

```java
BatchOptions opts = BatchOptions.timeout("mixed-io", java.time.Duration.ofMillis(5000))
        .parallelism(6)
        .taskType(TaskType.IO_BOUND);   // 统一 5 秒超时

TaskBatchResult<Object> result = par.map( tasks, task -> {
    if (task instanceof HttpRequest) {
        return httpClient.execute((HttpRequest) task);   // HTTP 调用
    } else if (task instanceof DbQuery) {
        return jdbcTemplate.query((DbQuery) task);        // DB 查询
    } else {
        return cacheClient.get((CacheKey) task);          // 缓存读取
    }
}, opts);

// fail-fast: 任何一个失败，其余自动取消
String report = result.reportString();
```

关于超时：用统一的 `BatchOptions.timeout` 即可，不需要每个任务设不同超时。原因：
- 框架级超时是"兜底"，防止任务永远挂起
- 如果某个调用需要更细粒度的超时，在任务内部自己处理（比如 HTTP client 的 connectTimeout/readTimeout）
- 这样保持 `BatchOptions` 简洁，任务逻辑自包含

---

## 通用模式

### 1. 超时必须设

不设超时 = 任务可能永远挂起。API 不提供默认超时：`timeout(name, Duration)` 与
`inheritTimeout(name)` 两个工厂必须显式二选一——都不调用就构造不出选项对象。根据业务场景
显式设置：

```java
// 快速 HTTP 调用
BatchOptions.timeout("http", java.time.Duration.ofMillis(3000)).taskType(TaskType.IO_BOUND);

// 数据库查询
BatchOptions.timeout("db", java.time.Duration.ofMillis(30000)).taskType(TaskType.IO_BOUND);

// 文件处理
BatchOptions.timeout("file", java.time.Duration.ofMillis(120000)).taskType(TaskType.IO_BOUND);
```

### 2. 并行度要匹配资源

`parallelism` 不是越大越好，要匹配下游资源：

| 场景 | 推荐 parallelism | 原因 |
|------|-------------------|------|
| HTTP 调用 | 5-10 | 别把下游打爆 |
| DB 查询 | = 连接池大小 | 超了就排队等连接 |
| 缓存读取 | 10-20 | 缓存通常能扛更高并发 |
| 文件 IO | 3-5 | 磁盘 IO 是瓶颈 |

### 3. 用 report() 快速判断

```java
// 一行看全貌
String report = result.reportString();
// "SUCCESS:8,USER_FAILURE:1,FAIL_FAST:1 | firstException=..."

// 结构化访问
TaskBatchResult.BatchReport r = result.report();
Map<TaskOutcome, Integer> counts = r.stateCounts();
Throwable firstError = r.firstException();
```

生产环境中，可以把 `reportString()` 打到日志里，配合 `completions()` 的可用终态快照做监控告警（见 G1）。
返回结果已确定；资源关闭仍是尽力保证。清理超时时查看 `unfinishedBodies()`，
`bodyCompletionConfirmed()` 只覆盖直接任务，嵌套任务需分别确认。

### 4. 异常不要吞

在 lambda 里 catch 异常返回 null 是最常见的坑：

```java
// 错误: 吞掉了异常，report 永远显示 SUCCESS:10
par.map( items, item -> {
    try {
        return riskyCall(item);
    } catch (Exception e) {
        return null;  // 隐藏了失败！
    }
}, opts);
```

让异常自然抛出，框架会自动记录到 `report()` 里，fail-fast 也会正确触发。

---

## 反模式

**1. `parallelism=Integer.MAX_VALUE`**

```java
// 错误: 等于没有并发控制
BatchOptions.timeout("bad", java.time.Duration.ofMillis(3000)).parallelism(Integer.MAX_VALUE);
```

正确做法：设一个合理的值，匹配下游资源。

**2. 不设超时**

```java
// 错误: 根级调用必须显式声明超时 —— 两个工厂都不调用根本拿不到选项对象
// BatchOptions.name("bad");            // 不存在这种写法
BatchOptions.timeout("bad", Duration.ofSeconds(5));   // 正确：显式超时
BatchOptions.inheritTimeout("nested");                // 正确：继承外层 scoped task 的 deadline
```

正确做法：根据场景在两个工厂之间显式二选一。根级调用不能选 `inheritTimeout`——没有外层 scoped task 时 `Par.map` 会抛 `IllegalArgumentException`。

**3. 在 lambda 里 catch 异常返回 null**

```java
// 错误: 隐藏失败，report 永远是 SUCCESS
par.map( items, item -> {
    try {
        return call(item);
    } catch (Exception e) {
        log.error("failed", e);
        return null;  // 框架认为这是成功!
    }
}, opts);
```

正确做法：让异常抛出，用 `reportString()` 统一处理。

---

> 完整测试代码：[BatchBestPracticesTest.java](https://github.com/monadrome/parallel-in-scope/blob/main/demo/src/test/java/demo/article/BatchBestPracticesTest.java)
