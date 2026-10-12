# 执行器边界诊断与资源测量

## 蒸馏摘要

- 核心出发点：让借用 executor 的关闭、wrapper 身份与框架资源边界可被正确诊断，
  不把已接受的限制伪装成新缺陷。
- 不变量与拟议 MUST：MUST 保留 supplied 提交目标；NO_ISSUE MUST 只表示已记录结构无环。
  shutdown 预检 MUST NOT 被称为消除关闭竞态；窗口 MUST NOT 被称为准备内存或全局请求上界。
- 被否决方向及理由：不直接换固定 timeout action 池，阻塞回调可能饿死后续取消；
  不用惰性准备破坏 TTL 捕获时点与 prepared Future 身份，不一概禁止正常 CallerRunsPolicy。

## 承接的发现与证据限制

2026-10-04/06 核心审计的 source-only S3/S5、contract-informed C6/C7 尚需在最新图模型上
复核；历史探针不是当前版本验证：

- JDK CallerRunsPolicy 在饱和且未 shutdown 时 inline，在已 shutdown 时静默丢弃任务。
  历史公开 map 探针直到有限 deadline 才以 TIMEOUT 收敛，不能报告永久挂起。
  优先明确借用 executor 的关闭顺序，再评估稳定 shutdown 的快速诊断。
- bare 与 TTL wrapper 注册为不同 supplied 身份；历史同池饥饿报告 NO_ISSUE 符合当时的
  身份契约。先复核 `5c1887a` 的 TaskGraph/ExecutorGraph；资源身份投影若改变契约须单独
  决定，不解包改变真实提交去向，也不合并可能改变行为的一般 wrapper。
- 全量准备仍可能按输入数量占内存，批内窗口不限制总请求负载。先测 TTL 捕获、准备保留、
  timeout 同时到期、阻塞 JUL handler 与取消派发延迟；未测量就不写性能数字。
  应用入口优先使用已有信号量或限流，不引入新的库内请求调度器。
- source-only-2026-10-04 的 F6 null mapper 诊断另需修复前复核：普通 map 的提交准备路径
  与 mapAndCombine 的 function 预检不同，不能用后者证明前者已修。应明确非空参数的
  有序校验，覆盖空输入与正常输入；NullAway 的编译约束不代替运行时调用方错误分类。

## 用户代码与验收

当前正确的所有权顺序，改后仍相同，池由用户最后关闭：

```java
runtime.close();
boolean drained = runtime.awaitQuiescence(grace);
log.info("drained=" + drained + ", snapshot=" + runtime.snapshot());
pool.shutdown();
```

消除的是将静默丢弃误读为业务超时、将诊断无环误读为没有饥饿风险、将滑窗误读为全局
内存上界的误用；不承诺关闭会强制停止任意 body。单纯文档澄清无需签名迁移。
任何新的快速失败、资源身份或有界调度行为须补 before/after 案例、并发交错和迁移说明后实施。
