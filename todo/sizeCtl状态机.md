# sizeCtl：一个 int 承载的状态机

> CHM 没有全局锁，却有一个全局状态——容量控制寄存器。
> 基于 JDK 8（Corretto 8.412）源码。

CHM 的每个 bin 都有一把自己的锁，唯独容量和扩容这件事是"全局的"——它需要一个所有线程都能读、且只能被一个人改的状态。答案是一个 int：**sizeCtl**。它的秘密在于**多义**：同一个字段，在不同阶段承载四种完全不同的含义。

## 一、四种状态，一个字段

| 阶段 | sizeCtl 的值 | 含义 | 谁写入 |
|---|---|---|---|
| 表未初始化 | **正数** = 归一化后的初始容量（无参构造 = 16） | 给 initTable 用的"建多大" | 构造器 |
| 初始化中 | **-1** | 排他：只有建表的线程能持有 | initTable（CAS） |
| 就绪（无扩容） | **阈值** = n - (n >>> 2) = 0.75 × n | 元素数 ≥ 它就触发扩容 | initTable 完成 / transfer 提交 |
| 扩容中 | **负数**（编码，见下节） | 进行中的扩容的"身份证 + 参与人数" | addCount / helpTransfer / transfer |

注意：sizeCtl 只在**表未初始化**时才是容量；表就绪后它是**阈值**（0.75n）；扩容中它是**负数**。同一个字段，读它的地方不同，语义完全不同——它是 CHM 的状态寄存器，不是容量字段。

## 二、扩容中的位编码：一个 int 存两样东西

```text
 31                                16 15                                 0
┌───────────────────────────────────┬───────────────────────────────────┐
│         resizeStamp(n)            │        2 + 活跃迁移线程数          │
└───────────────────────────────────┴───────────────────────────────────┘
        高 16 位：这一轮扩容的身份证          低 16 位：参与人数
```

`resizeStamp(n) = Integer.numberOfLeadingZeros(n) | (1 << 15)`：

- **唯一性**：n 是 2 的幂，不同长度对应不同的前导零个数 → 同一轮扩容的所有参与者（同一表长度）拿到相同 stamp，帮错轮子的人会被挡在门外；
- **负号是白送的**：`| (1 << 15)` 保证 rs 最高位为 1，左移 16 位后第 31 位恒为 1 → sizeCtl 必然为负。源码注释原话："Must be negative when shifted left by RESIZE_STAMP_SHIFT."——"负数 = 扩容中"这个判断不需要任何额外标志位，是位运算顺带产生的；
- 低 16 位从 2 起步（发起者写入 rs + 2），每加入一个线程 +1，每干完一个 -1，上限 MAX_RESIZERS = 65535。

## 三、状态转换：全部走 CAS

sizeCtl 的每一次变化都必须经 CAS——这是状态机一致性的全部保证，也是"没有全局锁却有一致全局状态"的原因。四个转换点：

| 转换 | 代码 | 防的是什么 |
|---|---|---|
| 建表排他（initTable） | CAS(sizeCtl, sc, -1) | 两个线程同时建表——只让一个赢，输的 Thread.yield() 自旋等表就绪（注释："lost initialization race; just spin"） |
| 发起扩容（addCount） | CAS(sizeCtl, sc, rs + 2) | **扩容风暴**：N 个线程同时越过阈值，只有第一个 CAS 成功当发起者；其余看到 sc < 0，转去帮忙而不是各自再发一轮 |
| 加入帮忙（helpTransfer） | CAS(sizeCtl, sc, sc + 1) | 两个帮助者同时报名——只有报名成功的真的去搬 bin |
| 干完退出（transfer 收尾） | CAS(sizeCtl, sc, sc - 1) | 每个线程结束时递减；减完发现"我是最后一个"的人负责提交 |

addCount 的触发循环是 `while (s >= sizeCtl && n < MAXIMUM_CAPACITY)`——每轮循环末尾重算 `s = sumCount()`，因为第一个线程 CAS 成功后 sizeCtl 已变负，后面的线程要么 break、要么走帮助分支。循环 + 重算，把"多线程同时越过阈值"收敛成"一次发起"。

帮助者的退出条件：

```text
sc == rs + MAX_RESIZERS ?  帮的人够多了（65535），别挤了
sc == rs + 1 ?             只剩一个线程在收尾，帮不上了
nextTable == null ?        迁移已提交，散场
transferIndex <= 0 ?       活都被领完了
以上都不成立 → CAS sc+1 → transfer(tab, nextTab)
```

## 四、帮忙的完整机制：报名 → 领活 → 干活 → 退出

### 两条入口：任何写者都可能顺手加入

加入扩容不只有 helpTransfer 一条路。写者 put 后的 addCount 检查计数时，如果发现 `sizeCtl < 0`（迁移进行中），同样会走 `CAS sc+1 → transfer(tab, nt)`。两条入口：

- 入口 1：addCount 里发现 sizeCtl < 0——写者 put 后，计数检查时顺路发现；
- 入口 2：put 撞到 FWD → helpTransfer(tab, f)——写入目标 bin 时发现"搬走了"。

两条入口最终都汇入 transfer 的同一个领活循环。设计意图：**迁移是全体写者"顺手"完成的**——谁碰到谁帮忙，不需要专门的迁移线程。

### 第一步：报名（helpTransfer）——四道校验

1. f 是 ForwardingNode 且 f.nextTable != null——挡掉无效的 FWD；
2. `nextTab == nextTable && table == tab && sizeCtl < 0`——还是同一轮扩容？新表没被提交、旧表还是当前表、迁移没结束；
3. break：sc == rs + MAX_RESIZERS（人满 65535）|| sc == rs + 1（只剩收尾的）|| transferIndex <= 0（没活了）；
4. CAS(sizeCtl, sc, sc + 1) 报名——失败回到 ② 重试；成功 → transfer(tab, nextTab)。

校验为什么这么多？因为帮助者拿到的是瞬间快照——它看到的 `nextTable`、`table`、`sizeCtl` 可能已经换了世界（迁移已提交、或新一轮扩容已开始）。校验 ② 保证"帮的是同一轮"（按当前表长度重算的 rs 与高位一致也隐含在其中）。帮错了轮子 = 白搬甚至搬错。

### 第二步：领活——transferIndex 分片，CAS 竞争

```text
transferIndex = 16（发起者设定，n = 旧表长度）
线程 A：CAS 16 → 12 成功 → 领走 [12, 16)，从 15 往下扫
线程 B：CAS 12 → 8 成功 → 领走 [8, 12)，从 11 往下扫
线程 C：CAS 8 → 4 成功 → 领走 [4, 8)，从 7 往下扫
…
transferIndex <= 0 → 没活了，转入收尾
```

- stride = max((n >>> 3) / NCPU, 16)——核多分小段、核少分大段，参与线程数与 CPU 匹配；
- 最后一段可能不满 stride（bound 归 0）；
- CAS 失败（别人抢先）→ 重读 transferIndex 再试——自旋领活，全程无锁；
- 领到段后从段尾往段头扫（`--i >= bound`）。

### 第三步：干活，第四步：退出

段内逐个 bin 的三种处理：**空 bin** 用 CAS 直接放 FWD（无锁）；**已是 FWD** 的直接跳过（防重复迁移）；**活 bin** 在 `synchronized (f)` 锁内完成三步——双检确认没被转发 → 拆链（全部新建节点）写入新表 {i, i+n} → 旧表 bin 原子替换为 FWD。退出即下一节的收尾：干完所有段后 `CAS sc-1`，不是最后一棒就带着"帮完了"直接 return——回到 putVal 用新表重试写入。

### 分工：sizeCtl 管"人"，transferIndex 管"活"

**洞察：**两个全局字段都是 CAS 协调，语义不同——sizeCtl 的 CAS 是**资格竞争**（输了就自旋或放弃），保证状态机一致（几轮扩容、谁收尾）；transferIndex 的 CAS 是**工作竞争**（输了重读再领），保证每段只被一个线程领走、不重不漏。一个管人，一个管活——CHM 把扩容的全局协调拆成两个无锁原语。

## 五、最后一棒：怎么知道"该我提交了"

1. 干完自己领的段 → `CAS(sizeCtl, sc, sc - 1)`；
2. `(sc - 2) == rs << 16`？——减完之后的计数恰好是 2，即活跃线程数归零：我是最后一个；
3. 是 → finishing：从 i = n 把全表复查一遍，确认每个 bin 都是 FWD（迁移期间写者可能往还没转发的旧 bin 里插入过新节点、空 bin 的 CAS 可能失败过——复查确保没有漏网的 bin）；
4. 提交：`nextTable = null; table = nextTab; sizeCtl = (n << 1) - (n >>> 1)`（新阈值 0.75 × 2n）。

计数从 2 起步，所以"退出后 (sc - 2) == rs<<16" ⇔ "活跃线程数归零" ⇔ 我是最后一个。提交只能由最后一棒做：提前提交会让还没搬完的 bin 的数据"消失"。这个 CAS 计数循环（rs+2 → +1/-1 → 归零）本身就是一次"多线程协作的屏障"。

## 六、两个特例：逃生门与硬上限

**OOM 逃生门。** transfer 创建新表失败时：`sizeCtl = Integer.MAX_VALUE`——不是回退到旧阈值，而是把阈值抬到"永远够不着"。效果：后续 put 的 `s >= sizeCtl` 不再成立，**放弃扩容，带着旧表继续跑**，而不是崩溃或死循环。

**硬上限。** addCount 的 while 条件里有 `n < MAXIMUM_CAPACITY`（1 << 30）——表长到 2^30 就不再扩了。另外两条路径：tryPresize（putAll 预扩）在表未初始化时走"CAS -1 建表"同一套路；remove 走 addCount(-1, -1)，check < 0 直接跳过扩容检查——**删除永远不触发扩容**。

## 七、生命周期总图

```text
[初始容量：正数] ──CAS -1（initTable 排他）──▶ [-1：初始化中]
      ▲                                              │
      │                                         建表完成，写入 0.75n
      │                                              ▼
      │                                     [阈值 0.75n：就绪]
      │                                              │
      │                                 put 越过阈值 → CAS rs+2
      │                                              ▼
      │                                     [负数：扩容中]
      │                                   （stamp + 线程计数，他人随时 CAS +1 加入）
      │                                              │
      └────── 最后一棒提交，写入 0.75 × 2n ──────────┘
                 （回到"阈值"状态，等待下一轮扩容）
```

一个值得注意的细节：阈值始终是 `n - (n >>> 2)`（0.75n），三个地方写的是同一个公式——initTable 完成时、transfer 提交时、归一化容量。0.75 的负载因子贯穿 sizeCtl 的一生。

## 总结：三个洞察

1. **一个字段的状态机，CAS 是唯一的推进方式。** 没有全局锁，但全局状态依然一致——因为每一次状态转换都是原子 CAS，输的人自旋或转去帮忙。
2. **位编码白送标志位。** "负数 = 扩容中"不是显式存储的状态，是 resizeStamp 左移后第 31 位恒为 1 的必然结果；同一轮扩容的身份也编码在高位里，帮错轮子的人直接被挡在门外。
3. **计数是协作的屏障。** rs+2 起步、+1 报名、-1 退场——这个 CAS 计数让"最后一个线程"可判定，也让提交动作只发生一次。

## 附录 · 源码关键段（JDK 8）

```java
// 常量：位布局的约定
private static final int DEFAULT_CAPACITY = 16;
private static int RESIZE_STAMP_BITS = 16;              // 高 16 位存 stamp
private static final int MAX_RESIZERS = (1 << (32 - RESIZE_STAMP_BITS)) - 1;  // 65535
private static final int RESIZE_STAMP_SHIFT = 32 - RESIZE_STAMP_BITS;         // 16

// 身份证：由表长度 n 唯一决定；| (1<<15) 保证左移后为负
static final int resizeStamp(int n) {
    return Integer.numberOfLeadingZeros(n) | (1 << (RESIZE_STAMP_BITS - 1));
}

// 建表：CAS -1 排他，输者自旋
private final Node<K,V>[] initTable() {
    while ((tab = table) == null || tab.length == 0) {
        if ((sc = sizeCtl) < 0)
            Thread.yield();                 // lost initialization race; just spin
        else if (U.compareAndSwapInt(this, SIZECTL, sc, -1)) {
            try {
                int n = (sc > 0) ? sc : DEFAULT_CAPACITY;
                table = tab = new Node<?,?>[n];
                sc = n - (n >>> 2);         // 0.75n
            } finally {
                sizeCtl = sc;
            }
            break;
        }
    }
    return tab;
}

// 触发：addCount 越过阈值；循环末尾重算 s，收敛"同时越过"的多个线程
while (s >= (long)(sc = sizeCtl) && (tab = table) != null && (n = tab.length) < MAXIMUM_CAPACITY) {
    int rs = resizeStamp(n) << RESIZE_STAMP_SHIFT;
    if (sc < 0) {                           // 已有迁移：加入帮忙
        if (sc == rs + MAX_RESIZERS || sc == rs + 1 ||
            (nt = nextTable) == null || transferIndex <= 0)
            break;
        if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1))
            transfer(tab, nt);
    }
    else if (U.compareAndSwapInt(this, SIZECTL, sc, rs + 2))
        transfer(tab, null);                // 自己发起：负数从此开始
    s = sumCount();
}

// 帮忙：helpTransfer——四道校验 + CAS 报名（入口 2）
final Node<K,V>[] helpTransfer(Node<K,V>[] tab, Node<K,V> f) {
    Node<K,V>[] nextTab; int sc;
    if (tab != null && (f instanceof ForwardingNode) &&
        (nextTab = ((ForwardingNode<K,V>)f).nextTable) != null) {
        int rs = resizeStamp(tab.length) << RESIZE_STAMP_SHIFT;
        while (nextTab == nextTable && table == tab &&
               (sc = sizeCtl) < 0) {                 // ② 还是同一轮扩容？
            if (sc == rs + MAX_RESIZERS || sc == rs + 1 ||
                transferIndex <= 0)                  // ③ 人满 / 只剩收尾 / 没活
                break;
            if (U.compareAndSwapInt(this, SIZECTL, sc, sc + 1)) {   // ④ 报名
                transfer(tab, nextTab);
                break;
            }
        }
        return nextTab;
    }
    return table;
}

// 领活：transferIndex CAS 分片（自旋重读再试）
while (advance) {
    int nextIndex, nextBound;
    if (--i >= bound || finishing)
        advance = false;
    else if ((nextIndex = transferIndex) <= 0) {
        i = -1;                              // 没活了，进入收尾
        advance = false;
    }
    else if (U.compareAndSwapInt(this, TRANSFERINDEX, nextIndex,
                                 nextBound = (nextIndex > stride ?
                                              nextIndex - stride : 0))) {
        bound = nextBound;                   // 领走 [nextBound, nextIndex)
        i = nextIndex - 1;                   // 从段尾往段头扫
        advance = false;
    }
}

// 收尾：最后一个线程提交
if (U.compareAndSwapInt(this, SIZECTL, sc = sizeCtl, sc - 1)) {
    if ((sc - 2) != resizeStamp(n) << RESIZE_STAMP_SHIFT)
        return;                             // 不是最后一个，走人
    finishing = advance = true; i = n;      // 全表复查
}
if (finishing) {
    nextTable = null;
    table = nextTab;                        // 新表上岗
    sizeCtl = (n << 1) - (n >>> 1);         // 0.75 × 2n，回到正数
    return;
}

// OOM 逃生门
} catch (Throwable ex) {
    sizeCtl = Integer.MAX_VALUE;            // 放弃扩容，带病运行
    return;
}
```
