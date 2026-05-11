# Par.map 受检异常签名决策（v0.3）

> 状态：**决策提案，待拍板**。本文自包含说明 batch 路径 `Par.map` 元素函数的
> 受检异常问题与选项，不依赖其他文档；所有现状结论带 `path:line` 锚点并内联关键代码。
> 基线：`dev/v0.3.0` 分支 HEAD，全量测试绿。

## 1. 现状

`Par.map` 是批路径唯一公开入口，只有一个重载（`Par.java:103-104`）：

```java
public <T, R> TaskBatchResult<R> map(
        @Nullable Collection<T> elements, Function<? super T, ? extends R> function, BatchOptions options)
```

`java.util.function.Function.apply` 不声明受检异常，因此元素体里做任何会抛受检异常的
IO（HTTP、JDBC、文件），用户都必须手工 try/catch 包装成非受检异常，再由库的失败
归因拆开——包装与拆包全是仪式代码。

**关键事实：执行管道本来就是 `Callable` 形态。** `mapWhileOpen` 在内部把 `Function`
包成 `Callable` 再进入统一提交通道（`Par.java:196`）：

```java
item -> () -> function.apply(item)
```

后续 `TaskSubmissions.prepare` 以 `Callable` 包装进 `ScopedCallable`，其
`call()` 本就声明 `throws Exception`（`ScopedCallable.java:49`），任何异常统一记录为
`USER_FAILURE`。也就是说"会抛的工作"在执行内核里是一等公民，受检异常只是被公开
签名挡在了门外。

**库内其他入口全部受检友好**，`map` 是唯一例外：

| 入口 | 任务体签名 |
|---|---|
| `Par.submit`（`Par.java:125`） | `Callable<T>`（`call() throws Exception`） |
| group 成员任务 | `Callable<T>` |
| group combine（`CombineFunction.java:35`） | `R apply(...) throws Exception` |
| **`Par.map`** | `Function<? super T, ? extends R>`（**不可抛**） |

按 JDK 习语，"会抛的工作"的标准形态是 `Callable`；`Function` 是"纯映射"的形态。
批处理的元素体天然是"工作"而非"纯映射"。

## 2. 目标

1. 元素函数可以直接声明受检异常，调用点零包装。
2. 与库内其他入口的"会抛"语义对齐：同一类工作在任何入口的写法一致。
3. 改动只动公开签名，执行管道与失败归因零变化。

## 3. 约束

- **Java 8 基线**：只能用 Java 8 语言与 API 能力。
- **lambda 调用点源码兼容**：现有 `map(elements, x -> ..., options)` 形式的调用
  必须不重新编译语义地通过；不允许把全体用户推向显式类型标注。
- **0.x 破坏性窗口**：源码/二进制层面的窄破坏可接受，但必须一次定稿并提供迁移写法。
- **不新增公开概念**超出必要：除一个函数式接口外不引入新类型。

## 4. 不变量

1. **失败归因不变**：元素体抛出的任何异常（受检或非受检）经 `ScopedCallable`
   统一记录为 `USER_FAILURE`；改签名只是让受检异常不再要求调用方手工包装。
2. **执行管道零改动**：内部已是 `Callable` 形态（`Par.java:196`），适配层随签名
   替换自然消失，提交、窗口、取消、deadline 路径全部不动。
3. `map` 的既有契约不变：`null`/空集合返回空结果、元素快照语义、executor 绑定
   语义、`BatchOptions` 语义（`Par.java:86-101` 的 javadoc 承诺）全部保留。
4. 泛型变型不变：参数保持 `? super T` / `? extends R` 的使用点变型。

## 5. 选项分析

### 选项 A：新增重载 `map(Collection, ThrowingFunction, BatchOptions)` —— 不可行

与现有 `map(Collection, Function, BatchOptions)` 共存时，两个函数式接口对无显式
类型的 lambda 调用点产生二义性：`map(list, x -> body, opts)` 无法被 javac 解析到
唯一方法，**全体 lambda 调用点编译失败**。这恰是最需要保护的调用形态（约束 2），排除。

### 选项 B：改签名为库自定义 `ThrowingFunction<T, R>` —— 推荐

新增公开函数式接口（root 包，与 `TaskListener` 等并列）：

```java
@FunctionalInterface
public interface ThrowingFunction<T, R> {
    R apply(T value) throws Exception;
}
```

`Par.map` 的参数类型由 `Function<? super T, ? extends R>` 替换为
`ThrowingFunction<? super T, ? extends R>`，内部 `item -> () -> function.apply(item)`
适配层删除，`Callable` 直接由元素函数充当。

兼容性账目：

| 调用点形态 | 影响 |
|---|---|
| lambda（`x -> ...`） | **源码兼容**：lambda 不绑定具体函数式接口类型 |
| 方法引用（`service::call`） | **源码兼容**，且抛受检异常的方法引用从非法变合法 |
| 持有 `Function` 变量再传入 | **源码破坏**：需改持 `ThrowingFunction`，或传 `fn::apply` 一行适配 |
| 二进制 | 不兼容（方法描述符变化），0.x 窗口内接受 |

### 选项 C：改收 `Function<T, Callable<R>>` —— 否决

调用方仍要多包一层 `x -> () -> io(x)`，只是把手工包装从异常维度挪到结构维度，
问题没解决反而多一层嵌套；与"元素体即工作"的心智模型相悖。

### 选项 D：维持现状 + 文档教用户包装 —— 否决

把仪式代码文档化等于承认签名选错但永不修；0.3 冻结后修正成本只会更高。

## 6. 推荐

**选项 B**。它是唯一同时满足目标 1 与约束 2 的方案；执行管道与失败归因零变化
（不变量 1、2），风险集中在公开签名本身，而 0.x 正是变更成本最低的窗口。

## 7. 波及面与落地清单

- 实现：新增 `ThrowingFunction.java`（含 `package-info` 级别的 nullness 约定遵循
  包级 `@ParametersAreNonnullByDefault`）；`Par.map` 签名与 javadoc（`Par.java:97-104`）；
  删除 `mapWhileOpen` 内的 `Function→Callable` 适配（`Par.java:176-199`）。
- 测试：现有 `map` 测试调用点（lambda 形态应零改动通过）；新增元素体直接抛
  受检异常 → `USER_FAILURE` 的归因用例。
- 文档：user-guide 批路径章节更新；`migration-v0.3.md` 记录签名替换与
  `fn::apply` 迁移写法。

## 8. 联动条件（独立情形说明）

库内另有一项将 group 声明面统一为 `Callable` 形态的公开面重做提案。若该提案与本
变更同窗口落地，则三个入口（`Par.submit`、group 任务/combine、`Par.map`）的
"会抛"语义全部对齐，迁移说明可在同一篇 migration 文档中一次交付。**即使该提案
不落地，本变更独立成立**——`map` 与 `Par.submit` 两个现存入口的对齐理由已经充分。

## 9. 待拍板点

1. 确认选项 B：改签名而非新增重载/维持现状。
2. 确认新类型命名 `ThrowingFunction`（备选 `ThrowingMapper`；推荐沿用 JDK
   `Function` 词根以降低认知成本）。
