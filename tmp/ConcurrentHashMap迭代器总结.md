# ConcurrentHashMap 迭代器总结

> 基于 JDK 8（Corretto 8.412）源码，结论均已实测验证。

**本文回答三个问题：**

| 问题 | 答案 |
|---|---|
| 扩容迁移中，迭代器会重复访问同一个节点吗？ | **不会**——遍历结构上就排除了，与锁无关 |
| 迭代和锁是什么关系？ | **直接无关**——迭代全程无锁，不被锁阻塞，也不阻塞别人 |
| 会不会漏看？ | **会**——迁移期间插入到"已扫过 bin"的元素本趟不可见；**下一趟全部找回，只影响当趟** |

目录：

1. [迭代器行走的三个事实](#一迭代器行走的三个事实)
2. [为什么不会重复：两刀切](#二为什么不会重复两刀切)
3. [漏看：什么时候发生](#三漏看什么时候发生)
4. [锁与迭代：直接无关，间接有关](#四锁与迭代直接无关间接有关)
5. [批量操作（forEach / search / reduce）：同一套机制](#五批量操作foreach--search--reduce同一套机制)
6. [实验验证](#六实验验证)

---

## 一、迭代器行走的三个事实

迭代器（`Traverser`）的行为由三个事实决定。理解了它们，"不会重复"就是顺理成章的结果。

### 事实 1：旧表的每个 bin 恰好被访问一次

迭代器沿着 bin 的下标向前走，`baseIndex` 只增不减：

```text
扫描顺序：0 → 1 → 2 → … → n-1
每个 bin 访问一次，访问序号 1–n 各一次
```

步长为 `baseSize`（初始表长度），在初始表上退化为顺序扫描。**任何 bin 不会扫第二遍。**

### 事实 2：撞到 ForwardingNode 就"下降"，只扫 {i, i+n}

迁移进行时，`transfer` 每转发完一个 bin，就把旧表该 bin 替换成 `ForwardingNode`（转发节点，指向新表）。迭代器撞上它时跳到新表，但**不是从新表开头重新扫**：

```text
旧表（n = 16）                       新表（2n = 32）
┌──┬──┬──┬──┬──┬─────┬──┬──┬──┐    ┌──┬──┬──┬──┬──┬────┬──┬──┬──┬──┐
│ 0│ 1│ 2│ 3│ 4│ FWD │ 6│ 7│ …│    │ 4│ 5│ 6│ 7│ 8│ …  │21│ …│ …│ …│
└──┴──┴──┴──┴──┴─────┴──┴──┴──┘    └──┴──┴──┴──┴──┴────┴──┴──┴──┴──┘
   bin 5 已转发（X 原本在这里）        只有 {5, 21} 会被这次下降访问
                                     （X 的落点二选一）
```

只访问两个 bin：`i` 和 `i + n`（n = 旧表长度）——旧 bin i 迁移后正好分裂成这两个。扫完弹回旧表，从 `i + baseSize` 继续。

旧 bin 6 的下降扫 {6, 22}，旧 bin 7 的下降扫 {7, 23}……**各次下降互不重叠**。

### 事实 3：节点迁移的终点 = 下降覆盖的集合 = {i, i+n}

`transfer` 把旧 bin i 的链表按 hash 的一位拆成两半，放回新表 bin `i` 和 `i + n`。所以一个节点从旧 bin i 出发，**只可能出现在新 bin i 或 i+n**——没有第三条路。

> **下降能扫到的集合，恰好就是节点能迁到的集合。** 事实 2 和事实 3 是同一件事的两面。

---

## 二、为什么不会重复：两刀切

**第一刀：bin 的访问次数 ≤ 1。**（事实 1）旧表每个 bin 恰好被访问一次；"在 bin i 处下降"发生在迭代器到达 bin i 的那一刻，而 bin i 只会被到达一次，所以下降也至多发生一次。

**第二刀：访问一个节点的唯一入口，是它所在的 bin。**（事实 2 + 3）节点 X 在任意时刻只存在于一个 bin：迁移前在旧 bin i，迁移后在新 bin {i, i+n}。能碰到 X 的路径只有两条——直接扫到旧 bin i，或"在旧 bin i 处下降"扫到新 bin {i, i+n}——而这两条路径都要经过旧 bin i 本身，它只被访问一次。

组合起来，迭代器到达 bin 5 时只有两种可能，**二者互斥**：

- **分支 A：迭代器先到**（bin 5 还是活 bin）→ 扫旧表访问 X → transfer 转发，X 迁入新表 → 迭代器早已越过 bin 5，不会再下降 → **X 共 1 次**。
- **分支 B：迁移先到**（bin 5 已转发）→ X 已在 {5, 21} → 迭代器到达 bin 5 见 FWD → 下降访问 X → **X 共 1 次**。

不存在"先扫旧表、之后又下降"的第三个分支：下降只发生在"到达 bin 时它已转发"这一种情况里。分支的分界由迁移时机决定，但**无论时机怎么变，结论都是 1 次**。

---

## 三、漏看：什么时候发生

不会重复，不代表不会漏。漏看需要"迭代器先扫过、写入者后插入、迁移最后转发"三步连续踩中同一个 bin：

```text
时刻   迭代器               bin 5                   写入者
t1     扫过（当时为空）      ——                      ——
t2     ——                  put 落进 bin 5           Y 插入
t3     ——                  transfer 转发 → 新表 {5,21}
t4     已越过 bin 5，       —— → 本趟看不到 Y
       不会下降
```

**为什么 Y 落得进 bin 5？** 因为 bin 5 还没被转发——写入者只有撞到已转发的 bin 才会去新表。

**为什么本趟看不到？** 新表 {5, 21} 的唯一入口是"在旧 bin 5 处下降"，而迭代器已经过了 bin 5，入口已不可能发生。

**判据一句话：** 漏看 = 元素插入发生在"迭代器扫过该 bin"之后、"该 bin 被转发"之前。与"迁移是否正在进行"无关——迁移开始前插入、之后随 bin 被转发，同样漏。

**恢复：** 下一趟迭代器从"当前表"重新开始扫描，每个 bin 都会被覆盖，Y 已稳定在新表里，必然可见。**漏看只影响当趟。** 趟开始时就存在的元素永远不会漏（分支 A / B 必居其一）。

---

## 四、锁与迭代：直接无关，间接有关

**直接答案：迭代和锁没有直接关系。** 迭代器全程无锁——不取锁、不等待锁、不被锁阻塞，也不阻塞任何写者或迁移线程。锁是写给**写者**（put / remove / compute）和**迁移线程**（transfer）的互斥工具；迭代器只做 volatile 读（`tabAt` + `Node.next` / `Node.val`）。

间接上，锁通过维护一个不变量，让无锁迭代变得安全，分三点：

1. **可见性**：迭代器读的每个字段都是 volatile（bin 槽、`Node.val`、`Node.next`）。迁移在锁内完成"写新表 + 放 FWD"，volatile 读要么命中 FWD 放置之前（完整旧链），要么命中之后（FWD → 下降），不会撕裂。
2. **不变量**：锁维护"旧 bin 要么完整、要么 FWD"。双检防止重复转发；锁内三步保证旧链不动（迁移全部新建节点）、FWD 最后放。迭代的正确性依赖这个不变量，但迭代本身不参与维护它——只是"路过"。`helpTransfer`（写者撞到 FWD 时帮忙迁移）只有写者会调用，迭代器从不帮忙，撞到 FWD 只是借道下降。
3. **调度**：锁只决定"bin 何时被转发"，不参与迭代的行走。上面"两刀切"的证明只用到了遍历结构和不变量，**没有用到锁**——锁与不重复无关，只与"写写互斥"有关。

**对照**：Hashtable / Collections.synchronizedMap 是方法级全局锁，迭代要么在锁内（外部同步），要么裸读 + 并发写抛 ConcurrentModificationException；CHM 是 per-bin 锁 + 无锁迭代，不抛 CME，弱一致。

> **读无锁不是白来的，是弱一致性买来的。** 锁能买到"某个精确时刻的一致视图"，而 CHM 的弱一致性契约明确放弃了这个精确性——既然不承诺精确，迭代就不需要锁。

---

## 五、批量操作（forEach / search / reduce）：同一套机制

`forEach` / `forEachKey` / `search` / `reduce` 等批量操作，和普通迭代器**共享同一个遍历内核**——它们的实现类 `BulkTask` 把 `Traverser` 的代码完整复制了一份：

```java
/**
 * Base class for bulk tasks. Repeats some fields and code from
 * class Traverser, because we need to subclass CountedCompleter.
 */
abstract static class BulkTask<K,V,R> extends CountedCompleter<R> {
    Node<K,V>[] tab;        // same as Traverser
    Node<K,V> next;
    TableStack<K,V> stack, spare;
    int index;
    int baseIndex;
    int baseLimit;
    final int baseSize;
    int batch;              // split control

    /** Same as Traverser version */
    final Node<K,V> advance() { ... }   // 与 Traverser.advance() 逐字相同
    private void pushState(...) { ... }
    private void recoverState(...) { ... }
}
```

**为什么复制而不是继承？** 因为批量任务要并行——必须继承 `CountedCompleter` 才能挂到 `ForkJoinPool` 上，而 `Traverser` 不能当 ForkJoinTask 用。代价是代码重复，收益是**遍历语义一字不差**：同样的步进、同样的 FWD 下降只扫 {i, i+n}、同样的 pushState / recoverState 弹栈。

**并行怎么切分？** 入口处按并行度算 batch，执行时把初始表范围对半拆：

```java
public void forEach(long parallelismThreshold, BiConsumer<? super K,? super V> action) {
    ...
    new ForEachMappingTask<K,V>
        (null, batchFor(parallelismThreshold), 0, 0, table, action).invoke();
}

final int batchFor(long b) {
    long n;
    if (b == Long.MAX_VALUE || (n = sumCount()) <= 1L || n < b)
        return 0;                                        // 串行：batch = 0
    int sp = ForkJoinPool.getCommonPoolParallelism() << 2; // slack of 4
    return (b <= 0L || (n /= b) >= sp) ? sp : (int)n;
}
```

- `batchFor` 算出初始 batch：`commonPool 并行度 × 4`（源码注释 "slack of 4"），这就是文档里"batch 约为 commonPool 并行度的 4 倍"的出处；
- 任务执行时：batch > 0 → 把初始表范围 [index, baseLimit) 对半拆成两个子任务交给 commonPool；batch == 0 → 用 `advance()` 串行扫自己那段；
- 每个子任务负责初始表的一个**互不重叠**的范围，内部行走与普通迭代器完全相同。

**因此同样的保证成立：** 批量操作不会重复访问同一个节点（每个 bin 在整个批量任务树里至多被访问一次），也会漏看（迁移期间插入到已扫过 bin 的元素，且只影响本次调用）。`parallelismThreshold` 的语义也由此解释：`1` = 最大并行，`Long.MAX_VALUE` = batch 0 = 串行。

---

## 六、实验验证

两个探针都跑在 Corretto 8.412（JDK 8）上，与源码一致：

| 实验 | 内容 | 结果 |
|---|---|---|
| 随机压测 | 20 秒内 map 从 2^18 扩到 2^22（4 轮完整迁移），慢速遍历 6 趟、共 5,197,152 次访问 | **重复 0 次** |
| 确定性构造 | 装载到恰好低于扩容阈值 → 迭代器扫过 bin 0 → 向 bin 0 插入 6 个 key → 触发迁移（bin 0 最后转发） | 本趟**恰好漏掉这 6 个 key**、其余 196,609 个 key 各访问一次、**重复 0 次**；下一趟全部可见 |

---

## 附录 · 源码依据（JDK 8）

```java
// Traverser：普通迭代器的内核（KeyIterator / ValueIterator / EntryIterator 都继承它）
static class Traverser<K,V> {
    Node<K,V>[] tab;        // current table; updated if resized
    Node<K,V> next;
    TableStack<K,V> stack, spare;
    int index;
    int baseIndex;
    int baseLimit;
    final int baseSize;

    final Node<K,V> advance() {
        Node<K,V> e;
        if ((e = next) != null)
            e = e.next;
        for (;;) {
            Node<K,V>[] t; int i, n;
            if (e != null)
                return next = e;
            if (baseIndex >= baseLimit || (t = tab) == null ||
                (n = t.length) <= (i = index) || i < 0)
                return next = null;
            if ((e = tabAt(t, i)) != null && e.hash < 0) {
                if (e instanceof ForwardingNode) {
                    tab = ((ForwardingNode<K,V>)e).nextTable;  // 下降：只借道
                    e = null;
                    pushState(t, i, n);
                    continue;
                }
                else if (e instanceof TreeBin)
                    e = ((TreeBin<K,V>)e).first;
                else
                    e = null;
            }
            if (stack != null)
                recoverState(n);
            else if ((index = i + baseSize) >= n)
                index = ++baseIndex;            // 每个 bin 恰好访问一次
        }
    }
}

// 可见性的基础：bin 槽、val、next 全是 volatile（迭代无锁的前提）
static class Node<K,V> implements Map.Entry<K,V> {
    final int hash;
    final K key;
    volatile V val;
    volatile Node<K,V> next;
}
static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
    return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
}

// 迁移：与写者同一把锁、同一个双检；临界区内三步 volatile 写
synchronized (f) {
    if (tabAt(tab, i) == f) {
        // 拆链：全部新建节点（只有链尾 lastRun 被复用），旧链一根指针都不动
        for (Node<K,V> p = f; p != lastRun; p = p.next)
            ln = new Node<K,V>(ph, pk, pv, ln);   // 或 hn
        setTabAt(nextTab, i, ln);       // 新表低半区
        setTabAt(nextTab, i + n, hn);   // 新表高半区
        setTabAt(tab, i, fwd);          // 旧表 bin 原子替换为 ForwardingNode
        advance = true;
    }
}
```
