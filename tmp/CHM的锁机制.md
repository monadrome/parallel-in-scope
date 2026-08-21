# ConcurrentHashMap 的锁：边界画在哪里

> 能 CAS 就不锁，能局部锁就不全局锁，读永远不锁。
> 基于 JDK 8（Corretto 8.412）源码。

问 CHM 怎么加锁，不如问：**哪里不需要锁**。JDK 8 的答案写在一个 `putVal` 里——一个循环，三个分支，只有一个分支有锁。

## 一、锁的粒度跟着数据结构走

三代实现，三种锁的覆盖范围：

| 版本 | 锁的覆盖范围 | 并发度 |
|---|---|---|
| Hashtable | 整个表一把锁（所有方法 synchronized） | 1 |
| JDK 7 CHM | 默认 16 段，每段一把 ReentrantLock，段内整段锁 | 16 |
| JDK 8 CHM | 锁 bin 头节点（平均链长 ≈ 1）；空 bin 用 CAS，无锁 | 接近"每个 bin 并行" |

锁的粒度在收敛到**数据结构的最小一致单元**：JDK 8 的最小一致单元是一条链表（平均长度 ≈ 1），锁就缩到一条链。JDK 7 的 segment 本质是"为了少写 CAS 而粗化"的锁；JDK 8 用 CAS 补齐了细粒度，锁自然退到只剩"必须锁的地方"。

## 二、三把尺子，管三件事

CHM 的同步不是一个"锁"，而是三种机制按操作触及的范围分工：

| 机制 | 管什么 | 用在哪里 |
|---|---|---|
| volatile | 看到什么（可见性） | bin 槽、`Node.val` / `Node.next`、迁移的三步发布 |
| CAS | 放什么（单点状态翻转） | 空 bin 插入、`transferIndex` 领活、`sizeCtl`、计数 |
| synchronized (f) | 怎么改（结构变更） | 链表插入/删除、树操作、迁移拆链 |

**洞察：**三者的分界不是"并发程度"，而是"操作触及的范围"——一个字段用 volatile，一个槽位用 CAS，一条链用锁。**锁不是最小的同步单位，字段才是。**

## 三、putVal 的三个分支，就是锁的必要条件

```text
put(key, value)
  │
  ▼
tabAt(tab, i) == null ?
  ├─ 是 → casTabAt(tab, i, null, node)        【CAS，无锁：空 bin，没有结构】
  └─ 否 → f.hash == MOVED ?
           ├─ 是 → helpTransfer(tab, f)，然后重试   【bin 正在迁移：帮忙，去新表】
           └─ 否 → synchronized (f) { 双检 → 插入/更新 }  【锁 bin 头节点】
```

三个分支的先后顺序本身就是优先级：**能 CAS 就不锁**。双检 `tabAt(tab, i) == f` 处理"拿到锁之前 bin 已被迁移"——锁的对象是头节点，迁移会把头节点换成 ForwardingNode，双检失效即重试。

**洞察：锁的必要条件不是"多个线程可能同时写"，而是"写会破坏共享结构"。**空 bin 的写入是"无中生有"——CAS 一个槽就够，没有结构可破坏；链表的写入是"有中改有"——必须锁住整条链，结构才完整。

## 四、迁移和写者，共用一把锁

一个 bin 的一生是一次原子转换：

```text
[ 活 bin ] ──transfer：synchronized (f) 内三步──▶ [ ForwardingNode ]
    ▲                                                  │
    │ 写者：synchronized (f) 修改                       │ 写者：helpTransfer 帮忙后去新表
    └──────────────────────────────────────────────────┘
```

三步：① 双检 ② 拆链（全部新建节点）写新表 {i, i+n} ③ 旧表原子替换为 FWD。对写者而言不存在"正在迁移的 bin"：要么迁移前写旧表，要么迁移后写新表。

迁移用的锁和写者用的锁是同一把（都是 `synchronized (f)` + 双检）——不需要专门的迁移锁、不需要读写锁，一把 bin 锁就协调了写者与迁移者的全部关系。`transferIndex` 用 CAS 分片领活，让撞到 FWD 的写者（`helpTransfer`）顺手帮忙搬几个 bin：迁移是多线程协作的，锁只保证同一 bin 互斥，不同 bin 全程并行。

## 五、读永远无锁：锁的边界

| 锁内（synchronized (f)） | 无锁（volatile 读） |
|---|---|
| 写者：put / remove / compute / merge | get / containsKey |
| 迁移：transfer 转发 bin | 迭代 Traverser（含扩容中的下降） |
| | size() / isEmpty() |

读无锁安全，靠两个事实：① 迁移**不原地修改旧链**（全部新建节点，只有链尾被复用），旧 bin 要么完整、要么 FWD（volatile 发布）；② `Node.val` / `Node.next` 是 volatile，链上读到的一定是完整状态。

代价是弱一致性：迭代结果不精确、`size()` 是近似值、迁移期间会漏看。这是一笔明账——数据库锁的世界观是"读写都加锁，防止读到中间状态"；CHM 用**不可变发布**换掉了读锁，把一致性成本从"锁"转移给了"契约"（弱一致）。**读无锁不是白来的，是弱一致性买来的。**

## 六、三句话

1. **能 CAS 就不锁，能局部锁就不全局锁，读永远不锁。**
2. **锁的粒度 = 数据结构的最小一致单元。** JDK 8 的最小单元是一条链，锁就缩到一条链。
3. **锁保护的不是并发，是结构。** 并发天然存在，锁只是让结构的变化有序发生——仅此而已。

## 附录 · 源码关键行（JDK 8）

```java
// putVal：三个分支，锁只在最后一个
if (f == null)
    casTabAt(tab, i, null, new Node<K,V>(hash, key, value, null)); // CAS
else if ((fh = f.hash) == MOVED)
    tab = helpTransfer(tab, f);           // 帮忙迁移
else {
    synchronized (f) {                    // 锁 bin 头节点
        if (tabAt(tab, i) == f) { ... }   // 双检
    }
}

// transfer：和写者同一把锁、同一个双检；三步 volatile 写
synchronized (f) {
    if (tabAt(tab, i) == f) {
        ... // 全部新建节点，旧链不动
        setTabAt(nextTab, i, ln);         // 新表低半区
        setTabAt(nextTab, i + n, hn);     // 新表高半区
        setTabAt(tab, i, fwd);            // 旧表放 ForwardingNode
    }
}

// 无锁读的地基：volatile 字段
volatile V val;
volatile Node<K,V> next;
static final <K,V> Node<K,V> tabAt(Node<K,V>[] tab, int i) {
    return (Node<K,V>)U.getObjectVolatile(tab, ((long)i << ASHIFT) + ABASE);
}
```
