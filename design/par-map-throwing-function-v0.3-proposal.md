# Par.map 受检异常签名决策（v0.3）

> 状态：**已否决并关闭 —— 保留标准 `java.util.function.Function`**。用户已明确决定
> `Par.map` 继续使用现有签名，不新增、不采用公开 `ThrowingFunction`，不加受检异常便利
> 重载。本文件保留为决策历史：§1–§5 的分析结论仍然成立，但 §5 全部选项、§6 原推荐、
> §7 落地清单与 §9 待拍板点**均已否决，不是待办工作**。
>
> **不得重开**：后续编码 agent 不应再把 `ThrowingFunction` 当作优化或特性提出，除非用户
> 明确重新开启该决策。理由见 §6；被否决方案的残留记录见 §7。
>
> 本文自包含说明 batch 路径 `Par.map` 元素函数的受检异常问题与选项，不依赖其他文档；
> 分析部分的所有现状结论带 `path:line` 锚点并内联关键代码。
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
| group combine（v0.3 起为 `TaskGroup.CombineBody`） | `R apply(CombineContext) throws Exception` |
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

## 5. 选项分析（历史记录 —— 下列选项全部已被否决）

> 本节保留的是**当时的分析**，用于说明为什么每个方向被评估过。它们不是待选方案：
> 用户已决定维持现状（见 §6），本节任何一条都不再构成待办。

### 选项 A：新增重载 `map(Collection, ThrowingFunction, BatchOptions)` —— 不可行

与现有 `map(Collection, Function, BatchOptions)` 共存时，两个函数式接口对无显式
类型的 lambda 调用点产生二义性：`map(list, x -> body, opts)` 无法被 javac 解析到
唯一方法，**全体 lambda 调用点编译失败**。这恰是最需要保护的调用形态（约束 2），排除。

### 选项 B：改签名为库自定义 `ThrowingFunction<T, R>` —— **原推荐，已被用户否决**

> 否决理由：新增公开函数式接口会扩大公共表面积，并带来迁移与二进制兼容成本，而换来的
> 只是把受检异常的包装从调用方移到签名里——**结构化并发本身没有因此多一分保证**。
> 下面的实现方案与兼容性账目仅作历史记录，不再执行；§7 的落地清单同样作废。

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

## 6. 决定：保留标准 `Function`（原推荐选项 B 被否决）

用户明确决定：**`Par.map` 继续使用标准 `java.util.function.Function`**。不新增、不采用
公开 `ThrowingFunction`，不改变 `Par.map` 签名，不新增受检异常便利重载。需要做会抛受检
异常工作的调用方，自己在函数体内完成包装——例如捕获后改抛非受检异常，或返回一个领域
结果类型——库不替调用方决定这个策略。

理由：

- **JDK 标准 `Function` 让 API 更小、更熟悉**。调用方已经知道它，不需要为此再学一个
  词根相同的库内类型。
- **受检异常的包装属于调用方策略**，不是执行内核的能力。库内部本来就以 `Callable`
  形态执行（§1），失败归因已经是 `USER_FAILURE`；签名是否声明 `throws` 不改变任何一条
  执行、取消或 deadline 语义。
- **代价与收益不成比例**：第二个函数式接口会扩大公开表面积，并带来迁移与二进制兼容
  成本（§5 选项 B 的兼容性账目），而结构化并发本身**没有因此多一分保证**。

`Par.submit`、group 成员与 combine 仍保持 `Callable` / `CombineBody`（它们本就声明
`throws Exception`）；入口之间的形态差异是有意保留的现状，不是待修的不一致。

## 7. 波及面与落地清单 —— 已否决，不执行

> 以下清单描述的是**已被否决**的选项 B。保留此节只为让后续读者看到当初评估过的工作量，
> **不应据此创建任务、提交代码或修改文档**。实际落地是"零改动"：`Par.map` 签名、
> `mapWhileOpen` 适配层、测试与 migration 文档全部维持原样。

- ~~实现：新增 `ThrowingFunction.java`；`Par.map` 签名与 javadoc（`Par.java:97-104`）；
  删除 `mapWhileOpen` 内的 `Function→Callable` 适配（`Par.java:176-199`）。~~
- ~~测试：现有 `map` 测试调用点；新增元素体直接抛受检异常 → `USER_FAILURE` 的归因用例。~~
- ~~文档：user-guide 批路径章节更新；`migration-v0.3.md` 记录签名替换与 `fn::apply` 写法。~~

## 8. 联动条件 —— 随本决策一并失效

原文讨论的是"若 group 声明面统一为 `Callable` 形态的提案与本变更同窗口落地"的协同
迁移。由于本变更不再发生（§6），该联动条件没有对象；group 声明面本身的决策独立于本文，
不受影响。

## 9. 待拍板点 —— 已关闭

1. ~~确认选项 B：改签名而非新增重载/维持现状。~~ **已决：维持标准 `Function`（§6）。**
2. ~~确认新类型命名 `ThrowingFunction`。~~ **已失效：不新增该类型。**
