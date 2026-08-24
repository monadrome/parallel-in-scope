# 如何对待中断异常：一个被吞掉的 InterruptedException 引发的思考

并发编程里，中断异常 `InterruptedException`（下文简称 IE）是最容易被"顺手处理掉"的异常：catch 住之后什么都不做，或者打个日志继续跑。这些做法看似无害，却可能让整个程序静默挂死——这是最难排查的故障。

## 1. 一个真实的教训：SI-8938

2014 年有人向 Scala 报告：当 Future 里的代码抛出 IE（比如 `Thread.sleep` 被打断）时，这个 Future 既不成功也不失败，永远停留在"未完成"状态；如果 `Await.result` 超时无限，调用线程永久阻塞。异常唯一的痕迹是 ExecutionContext 默认打印到控制台的 failure report。

讨论持续六年，结论始终一致，后来在 Scala 2.13 修复：

> 任务抛出的 IE 必须让 future 以失败完成（自动包成 `ExecutionException`），绝不能把 future 留在未完成状态——异常必须让调用者可见。
这个原则对任何语言、任何异步框架都成立。

## 2. 先搞清楚：Java 的中断只是一个标志位

Java 的中断不是杀死线程，只是置一个标志：

```java
thread.interrupt();        // 置位：请求该线程停止当前工作
Thread.interrupted();      // 读取并清除标志位（静态方法，作用于当前线程）
thread.isInterrupted();    // 只读取，不清除
```

标志位有两类消费者：

1. 可中断的阻塞方法（`Thread.sleep`、`Object.wait`、`BlockingQueue.take/put`、`Future.get` 等）：进入阻塞时检查标志，被置位则抛 IE 并清除标志；
2. 非阻塞协作代码：主动检查标志，自己决定是否停止。

生命周期因此是：`interrupt()` 置位 → 阻塞方法抛 IE 并清除 → 处理者决定要不要恢复标志。

## 3. 三个正确动作，三个错误动作

| 动作 | 适用场景 |
|---|---|
| 传播（重抛 IE） | 当前代码不负责决定"被打断意味着什么"，交给上层处理 |
| 恢复标志（`Thread.currentThread().interrupt()`） | 必须捕获 IE（比如做清理），但调用者应当知道线程被打断过 |
| 屏蔽（`Uninterruptibles.getUninterruptibly`） | 不可中断的临界操作（如恢复现场），是例外而非常态 |

错误做法：

1. 空 catch：打断信号彻底消失，上层永远不知道线程被打断过；
2. 用 `Thread.interrupted()` 做检查：读取的同时消费了标志，后续阻塞方法再也看不到这次中断；
3. 把 IE 当业务异常：包装成业务错误继续跑——IE 的语义是"停止"，不是"重试"。

## 4. 核心思想：信号不丢失，意图不猜测

中断可能来自主动取消（`Future.cancel(true)`、`shutdownNow()`），也可能来自外部线程的 `interrupt()`。理论上应当区别对待，但实践中无法可靠区分——中断只是一个位，不携带来源信息。所以设计上要接受：

> 捕获到 IE 的代码不该猜测中断的意图，它的职责只是把这个信号完整地、不丢失地传递给知道该怎么做的人。

库代码尤其如此：方法被中断，可能是调用者取消了任务，也可能是调用者要关线程池。库不替调用者做决定，只保证信号不丢失——要么在签名上声明 `throws InterruptedException`，要么在不能重抛的地方恢复标志位。

如果想在框架内把中断统一表达为"取消"，也可以翻译（比如转成 `CancellationException`），但翻译时要把原始 IE 链为 cause——翻译不意味着销毁证据，诊断时仍能看到最初的异常。

## 5. 异步任务中的用法：future 必须到达终态

> 任务体内抛出的任何异常——包括 IE——都必须让 future 异常完成。绝不留一个永远 RUNNING 的 future。

原因很直接：等待方（`Future.get()`、`Futures.allAsList`）无法区分"任务还在跑"和"任务永远不会完成"。future 停在 RUNNING，调用者就只能在"无限等待"和"自己设超时"之间二选一，而超时引发的取消会进一步掩盖真正的原因。

因此任务包装器的正确姿势是原样重抛，什么都不捕获：

```java
@Override
public V call() throws Exception {
    try {
        return delegate.call();
    } catch (Throwable t) {
        throw t;      // 任何异常——包括 IE——原样流出，让 future 异常完成
    } finally {
        cleanup();    // 只清理，不吞异常
    }
}
```

任务代码也一样：在任务里调用可中断方法，不要把 IE 吞掉后继续跑——任务以失败结束，比调用者挂死好一万倍。

另一个易踩的坑在提交端：滑动窗口式的提交循环（"完成一个、提交下一个"）在等待时被打断，可能直接 return，把还没提交的那些 future 留在 RUNNING。这正是 SI-8938 的翻版：异常被吞（留在某个没人监听的 future 里），调用者挂死。修法很简单——退出循环前，把每一个不会再收到任务的 future 补成终态：

```java
// 提交循环被中断 / 拒绝 / 检测到取消而提前退出时：
for (int i = index; i < result.size(); i++) {
    if (reason != null) {
        result.get(i).setException(reason);   // 让批次 fail-fast，原因对调用者可见
    } else {
        result.get(i).cancel(true);           // 批次本身在取消，保持一致
    }
}
```

补全之后，无论发生什么，批次都必然到达终态：要么全部成功，要么失败原因在报告里可见，而不是靠超时兜底。

## 6. 总结

SI-8938 给我们的教训是：异常被吞掉不可怕，可怕的是被吞掉之后程序看起来一切正常。挂死几乎总是"某个异常被某个地方悄悄处理掉"的结果。
在 Python 之禅也提到：Errors should never pass silently. Unless explicitly silenced.

1. 捕获 IE 必须二选一：重抛，或 `Thread.currentThread().interrupt()` 恢复标志。空 catch 是反模式。
2. 公共 API 边界保留 `throws InterruptedException`：决定权交给调用者，不要替调用者翻译成业务异常。
3. 异步任务体内的 IE 必须让 future 异常完成：包装器不拦截、不吞、不翻译成成功。
4. 提交/调度循环放弃任务时，补全对应的 future：绝不让任何 future 停在 RUNNING 而永远等不到结果。
5. 不可中断的临界操作才用 `Uninterruptibles`：它是例外不是默认。
6. 用 `isInterrupted()` 做检查，不用 `Thread.interrupted()`：前者只读，后者会消费标志位。


