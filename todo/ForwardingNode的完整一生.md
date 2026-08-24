# ForwardingNode 的完整一生：CHM 扩容迁移全流程

> 从"扩容被点燃"到"新表上岗"——一个占位符如何协调所有参与者。
> 基于 JDK 8（Corretto 8.412）源码。

**ForwardingNode（转发节点）是迁移完成的标志物**：旧表 bin 一旦被搬空，就放一个 FWD 占位。它只有一个使命——告诉所有路过的人："这个 bin 已经搬走了，数据在新表里，从 `nextTable` 找。"

## 一、FWD 是什么：一个极简的哨兵

| 字段 | 普通 Node | ForwardingNode |
|---|---|---|
| hash | 真实哈希 | **MOVED = -1**（哨兵值） |
| key / val | 数据 | **null**（不携带数据） |
| next | 链表后继 | **null**（不参与链表） |
| nextTable | —— | **final**，指向迁移目标表（不可变） |

`hash == MOVED` 是所有人的统一判断入口：bin 槽里读到的头节点哈希为负且等于 -1，就知道"这个 bin 已迁移"。`nextTable` 用 final 修饰——目标一旦确定就不允许改，保证"指路"的可靠性。

## 二、迁移怎么被点燃：扩容触发链

写者 put 后，`addCount` 给计数 +1 并检查阈值：

```text
put / putAll / merge …
  │
  ▼
addCount(1, binCount)              计数 +1，检查阈值
  │
  ▼
s >= sizeCtl ?
  ├─ 否 → 无事发生
  └─ 是 → 已有迁移进行中（sizeCtl < 0）？
           ├─ 是 → CAS sizeCtl +1，transfer(tab, nt)         加入帮忙
           └─ 否 → CAS sizeCtl = rs + 2，transfer(tab, null)  自己当发起者
```

`resizeStamp(n) = Integer.numberOfLeadingZeros(n) | (1 << 15)`——由表长度 n 决定，同一轮扩容的参与者拿到相同的 stamp。迁移期间 `sizeCtl` 为负值，低 16 位是"参与迁移的线程数"：发起者写入 rs+2，每加入一个线程 +1，每干完一个 -1。正是靠这个计数，才能判断谁是"最后一个干完的线程"（见第六节）。

## 三、transfer 的总调度：领活制

- 发起者创建 2n 的新表，`transferIndex = n`（"待办上限"）；
- 每个参与的线程：`CAS(TRANSFERINDEX, nextIndex, nextIndex - stride)` 领走一段 `[nextIndex - stride, nextIndex)`；
- 从段尾往段头逐个 bin 迁移；
- `transferIndex <= 0` → 没活了，进入收尾。

stride = max(n >>> 3 / NCPU, 16)，让核多分小段、核少分大段。CAS 保证多个线程不会领到同一段——**不同 bin 全程并行，锁只在单个 bin 上短暂互斥**。另外整个 transfer 只创建**一个** fwd 实例，所有 bin 共用（它本身无状态，只背着一个 nextTable）。

## 四、单个 bin 的迁移：三种情况，只有一种上锁

领到一段 bin 后，逐个处理。每个 bin 有且只有三种状态：

| bin 当前状态 | 处理方式 | 同步机制 |
|---|---|---|
| 空 bin（tabAt == null） | casTabAt(tab, i, null, fwd)——直接放 FWD | **CAS，无锁** |
| 已是 FWD（f.hash == MOVED） | advance = true——跳过 | 无（防重复迁移） |
| 活 bin（有链表 / 树） | synchronized (f) 锁内三步 | **bin 锁 + 双检** |

空 bin 用 CAS 放 FWD——又一次"能 CAS 就不锁"。若 CAS 失败（并发写者抢先插入了节点），循环重试，这次走锁。

### 活 bin 的链分裂：lastRun 优化

```text
旧表 bin i 的链（括号 = hash 第 n 位）：
  A(0) → B(1) → C(1) → D(0) → E(0)

先找 lastRun：从链头扫到链尾，记录"从尾巴往前、位值不再变化"的起点。
这里 D、E 都是 0，lastRun = D。它后面的尾巴（D→E）整段复用，前面的节点（A、B、C）新建。

新表 bin i   （ln，位=0）：A'(新建) → D(复用) → E(复用)
新表 bin i+n （hn，位=1）：C'(新建) → B'(新建)
```

旧链 A→B→C→D→E **一根指针都没动**；D、E 同时被新旧两条链引用（共享尾巴）。这就是"旧 bin 在 FWD 放置前必须保持完整"的原因之一——新表还指着它的尾巴。树 bin 同理：全部新建 TreeNode，两侧都够大才建新 TreeBin，节点太少退化成链表（untreeify），只有一侧有节点时整棵树原样复用（`ln = (lc <= 6) ? untreeify(lo) : (hc != 0) ? new TreeBin(lo) : t`）。

### 锁内三步，写完新表再占旧位

```java
synchronized (f) {
    if (tabAt(tab, i) == f) {         // ① 双检：确认还没被别人转发（拿锁期间可能已迁）
        ... 拆链 → ln / hn（新建节点，旧链不动）...
        setTabAt(nextTab, i, ln);     // ② 新表低半区
        setTabAt(nextTab, i + n, hn); // ② 新表高半区
        setTabAt(tab, i, fwd);        // ③ 旧表 bin 原子替换为 FWD——唯一动旧表的一步
        advance = true;
    }
}
```

前两步只碰新表，最后一步才碰旧表。三步都是 volatile 写，锁外的人（读者、迭代器）不需要拿锁就能读到完整结果：要么读到完整旧链（FWD 放置前），要么读到 FWD（放置后）。

## 五、FWD 放好之后：三个消费者，各走各路

| 消费者 | 看到 FWD 的反应 |
|---|---|
| 写者 put / remove / compute | `f.hash == MOVED` → `helpTransfer` 帮忙搬一段 → 回新表重试 |
| 读者 get / containsKey | `eh < 0` → `f.find(h, k)` 借道 nextTable 继续找，无锁 |
| 迭代器 Traverser | 见 MOVED → pushState 记住旧表 → 下降，只扫 {i, i+n} |

get 的路径值得一提：它不关心"这是不是 FWD"，只看 `eh < 0` 就交给 `find()`。FWD 的 `find()` 用一层循环（注释："loop to avoid arbitrarily deep recursion on forwarding nodes"）在新表里继续找——如果新表也在扩容中，继续顺着嵌套的 FWD 往下找，直到找到或走到空。读者可能一次 get 穿越两张表。

第四类参与者是"自己人"：另一个迁移线程看到 MOVED 直接 `advance` 跳过——**这就是 FWD 防重复迁移的作用**：一个 bin 只可能被迁移一次，谁先占位谁说了算。

## 六、收尾：最后一棒

每个线程干完自己领的段后：

1. `CAS(sizeCtl, sc, sc - 1)`——参与者计数 -1；
2. 检查 `(sc - 2) == rs << 16`？——减完之后的计数恰好是 2，即活跃线程数归零：我是最后一个；
3. 是 → `finishing` 复查：从 i = n 把全表再扫一遍，确认每个 bin 都是 FWD（迁移期间写者可能往还没转发的旧 bin 里插入过新节点、空 bin 的 CAS 可能失败过——复查确保没有漏网的 bin）；
4. 提交：`nextTable = null; table = nextTab; sizeCtl = (n << 1) - (n >>> 1)`——新阈值 0.75 × 2n，sizeCtl 从"迁移中（负数）"回到"阈值（正数）"。

旧表连同里面的 FWD 一起退役：不再有引用时整张表被 GC。提交只能由最后一棒做——提前提交会让还没搬完的 bin 的数据"消失"。

## 总结：FWD 的三个角色

1. **占位**——旧表 bin 已搬空，禁止再写旧表：写者看到 MOVED 就转去新表；
2. **指路**——读者、迭代器顺着 final 的 `nextTable` 找到数据；
3. **防重**——迁移线程看到 MOVED 直接跳过，一个 bin 只搬一次。

回到锁的话题：FWD 的放置贯彻了 CHM 一贯的边界——**空 bin 用 CAS、活 bin 用锁**；而 FWD 本身无锁可加（读者永远无锁）。"旧 bin 要么完整、要么 FWD"这个不变量，由"旧链不动 + 最后一步原子替换"共同维护——它是无锁读、无锁迭代全部安全性的共同前提。

## 附录 · 源码关键段（JDK 8）

```java
// ForwardingNode：极简哨兵
static final class ForwardingNode<K,V> extends Node<K,V> {
    final Node<K,V>[] nextTable;                       // 指向迁移目标，final 不可变
    ForwardingNode(Node<K,V>[] tab) {
        super(MOVED, null, null, null);                // hash = -1，无 key/val/next
        this.nextTable = tab;
    }
    Node<K,V> find(int h, Object k) {                  // 读者借道：在新表里继续找
        outer: for (Node<K,V>[] tab = nextTable;;) { ... }
    }
}

// 触发：addCount 越过阈值
while (s >= (long)(sc = sizeCtl) && (tab = table) != null && (n = tab.length) < MAXIMUM_CAPACITY) {
    int rs = resizeStamp(n) << RESIZE_STAMP_SHIFT;
    if (sc < 0) {
        if (sc == rs + MAX_RESIZERS || sc == rs + 1 ||
            (nt = nextTable) == null || transferIndex <= 0) break;
        if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
            transfer(tab, nt);                          // 已有迁移：加入帮忙
    }
    else if (U.compareAndSwapInt(this, SIZECTL, sc, rs + 2))
        transfer(tab, null);                            // 自己发起（sizeCtl 为负 = 迁移中）
    s = sumCount();
}

// transfer 主循环（领活 + 每个 bin 的处理）
for (int i = 0, bound = 0;;) {
    // —— 领活：CAS transferIndex 拿一段 [nextBound, nextIndex) ——
    else if (U.compareAndSwapInt(this, TRANSFERINDEX, nextIndex,
                                 nextBound = (nextIndex > stride ? nextIndex - stride : 0))) {
        bound = nextBound; i = nextIndex - 1; advance = false;
    }
    ...
    else if ((f = tabAt(tab, i)) == null)
        advance = casTabAt(tab, i, null, fwd);          // 空 bin：CAS 放 FWD，无锁
    else if ((fh = f.hash) == MOVED)
        advance = true;                                 // 已被别人转发：跳过
    else {
        synchronized (f) {                              // 活 bin：锁内三步
            if (tabAt(tab, i) == f) {                   // 双检
                ... 拆链（新建节点，lastRun 尾巴复用）...
                setTabAt(nextTab, i, ln);
                setTabAt(nextTab, i + n, hn);
                setTabAt(tab, i, fwd);                  // 旧表放 FWD
                advance = true;
            }
        }
    }
    // —— 收尾：最后一个线程提交 ——
    if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
        if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
            return;                                     // 不是最后一个，走人
        finishing = advance = true; i = n;              // 全表复查
    }
    if (finishing) {
        nextTable = null;
        table = nextTab;                                // 新表上岗
        sizeCtl = (n << 1) - (n >>> 1);                 // 0.75 × 新长度
        return;
    }
}
```
