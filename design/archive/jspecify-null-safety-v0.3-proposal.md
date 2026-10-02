# JSpecify + NullAway Null 安全迁移实现方案（v0.3）

> 状态：**已实施**（2026-09-28 标注）。方案已全部落地，实施结果与差异见文末 §9 实施记录；
> §1–§8 保留为当时的决策依据，不再改动。现行约定以 `AGENTS.md` 与
> `docs/en/reference/nullability-annotations.md` 为准。
>
> 本文自包含说明从「JSR-305 + Checker Framework 混合注解、
> 无编译期校验」迁移到「JSpecify 注解 + Error Prone/NullAway 编译期强制」的完整方案。
> 基线：撰写时 `pom.xml` 版本为 0.2.0，全量测试绿。

## 1. 背景与目标

现状（`pom.xml:74-86`、`docs/zh/reference/nullability-annotations.md`）：

- Public API/回调用 JSR-305 `javax.annotation.Nullable`，内部类用
  `org.checkerframework.checker.nullness.qual.Nullable`，两者均 `provided` scope；
- 包级默认值靠 `@ParametersAreNonnullByDefault`（2 个 `package-info.java`）；
- 注解规模：主源码 21 个文件、约 136 处 `@Nullable`；
- **没有任何编译期强制**，注解只起 IDE 提示作用。

目标：

1. 注解体系统一为 JSpecify 1.0.1（`org.jspecify.annotations`），TYPE_USE 语义，
   包级 `@NullMarked` 替代 `@ParametersAreNonnullByDefault`；
2. 引入 Error Prone + NullAway 编译期检查，让 null 错误在 `mvn compile` 阶段失败；
3. 产物字节码保持 Java 8 不变，下游运行时要求不变。

选择 JSpecify 的理由：单一标准包取代混合策略；TYPE_USE 可标注泛型实参
（`ListenableFuture<@Nullable T>`），覆盖现在只有 checker-qual 能表达的场景；
Guava 33.6.0-jre 已把 `org.jspecify:jspecify:1.0.0` 作为 compile 依赖传递进来，
生态（Spring Framework 7 / Spring Boot 4、Kotlin、IntelliJ、NullAway）已收敛到它。

## 2. 构建环境：JDK 25 LTS

**JDK 25 是 LTS**（2025-09-16 GA；Oracle NFTC 免费期至 2028-09，扩展支持至 2030/2033；
下一个 LTS 是 2027-09 的 JDK 29）。

核心原则：**跑编译器的 JDK 与产物字节码版本解耦**。NullAway 官方 wiki 明确要求
构建 JVM 为 JDK 17+（Error Prone 2.43.0 起要求 JDK 21+），并推荐「新 JDK + `--release`」
作为面向旧字节码的标准做法。Guava 即如此：主编译 `--release 8`，另起一个
`--release 9` 的 execution 只编译 `module-info.java` 打 MR-JAR。

本机环境（已就绪）：

- 已安装 Eclipse Temurin **OpenJDK 25.0.4.1 LTS**（2026-08-18 最新补丁）至
  `~/Library/Java/JavaVirtualMachines/temurin-25.0.4.1.jdk`，
  `java_home -v 25` 可解析；
- Maven 构建统一使用 JDK 25：`export JAVA_HOME=$(/usr/libexec/java_home -v 25)`；
- `pom.xml` 的 `<release>8</release>` / `testRelease 11` 原样保留。

防呆：新增 maven-enforcer-plugin `requireJavaVersion [21,)`，在过低 JDK 上快速失败
并提示使用 JDK 25（Spring Boot 对同类问题采取了相同的 fail-fast 策略）。

## 3. pom.xml 变更

### 3.1 依赖

```xml
<!-- 移除 -->
<dependency>com.google.code.findbugs:jsr305</dependency>          <!-- provided -->
<dependency>org.checkerframework:checker-qual</dependency>        <!-- provided -->

<!-- 新增（compile scope，即默认 scope） -->
<dependency>
    <groupId>org.jspecify</groupId>
    <artifactId>jspecify</artifactId>
    <version>${jspecify.version}</version>  <!-- 1.0.1 -->
</dependency>
```

scope 决策：JSpecify 官方明确建议**不要**用 `provided`/optional——注解出现在公开 API
签名里，下游的 NullAway/Kotlin/IntelliJ 需要读到它们才能校验调用点；jar 体积极小，
且 Guava 本来就以 compile scope 传递它。故用默认 compile scope。

### 3.2 Error Prone + NullAway 接入 maven-compiler-plugin

```xml
<properties>
    <jspecify.version>1.0.1</jspecify.version>
    <errorprone.version>2.43.0+</errorprone.version>   <!-- 实施时取最新；2.43.0 起要求 JDK 21+ -->
    <nullaway.version>0.13.x</nullaway.version>        <!-- 实施时取最新；要求 EP 2.36+ / JDK 17+ -->
</properties>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <release>${java.release}</release>
        <testRelease>${maven.compiler.testRelease}</testRelease>
        <compilerArgs>
            <arg>-XDcompilePolicy=simple</arg>
            <arg>--should-stop=ifError=FLOW</arg>
            <arg>-Xplugin:ErrorProne -Xep:NullAway:ERROR -XepOpt:NullAway:AnnotatedPackages=io.github.monadrome.parallelinscope -XepOpt:NullAway:OnlyNullMarked=true</arg>
        </compilerArgs>
        <annotationProcessorPaths>
            <path>
                <groupId>com.google.errorprone</groupId>
                <artifactId>error_prone_core</artifactId>
                <version>${errorprone.version}</version>
            </path>
            <path>
                <groupId>com.uber.nullaway</groupId>
                <artifactId>nullaway</artifactId>
                <version>${nullaway.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### 3.3 新增 `.mvn/jvm.config`

JDK 16+ 上 Error Prone 需要开放 javac 内部包；Maven 进程内编译，参数走 `.mvn/jvm.config`：

```
--add-exports jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED
--add-exports jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED
--add-opens jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED
```

### 3.4 NullAway 模式选择

- 本次开启 `OnlyNullMarked=true`：只检查 `@NullMarked` 包，配合迁移一步到位；
- **暂不开启** `JSpecifyMode=true`（泛型/数组/varargs 完整语义，仍在完善中）；
  JDK 25 javac 已满足其 22+ 门槛，作为后续增量单独评估。

## 4. 源码迁移（21 个文件，约 136 处）

| 现状 | 迁移后 |
|---|---|
| `import javax.annotation.Nullable;` | `import org.jspecify.annotations.Nullable;` |
| `import org.checkerframework.checker.nullness.qual.Nullable;` | 同上（统一） |
| 方法声明前的 `@Nullable`（JSR-305 风格） | TYPE_USE 位置：返回类型前 `public @Nullable Throwable firstException()` |
| `@ParametersAreNonnullByDefault`（2 个 package-info） | `@NullMarked`（语义更强：覆盖参数、返回值、字段、泛型实参的全部 type-use） |

注意点：

- 静态嵌套/全限定类型的 TYPE_USE 语法：`Outer.@Nullable Nested`，不能写
  `@Nullable Outer.Nested`；
- 语义差复核：`@NullMarked` 下「未标注即非空」覆盖到字段与泛型实参，与现有文档
  约定一致，预计无需大规模补标，但需逐文件过一遍字段初始化路径（构造器保证非空）；
- AGENTS.md 的两套 Nullable 约定（public 用 javax、internal 用 checker）同步改为
  统一 `org.jspecify.annotations.Nullable`。

## 5. NullAway 报错修复

预期报错来源：

1. **Guava 33.4.1+ 已 `@NullMarked`**：NullAway 会更严格地校验调进 Guava 的调用点
   （可空返回值、泛型实参），无论是否开 JSpecifyMode——这是最主要的预期增量；
2. 库内部此前只靠约定保证的 null 流（如 `current()`、队列 poll 返回值）；
3. 测试代码同样过编译检查（testRelease 11 与 Error Prone 无冲突）。

修复手段优先级：补/调 `@Nullable` 标注 > `Objects.requireNonNull` 收窄 >
`@SuppressWarnings("NullAway")`（必须带原因注释，参考 Reactor 文档的分类注释法）。

若报错量超预期（>30 处），降级路径：先把 `-Xep:NullAway:WARN` 随注解迁移落地，
单独一次提交转 `ERROR`。

## 6. 文档同步

- `AGENTS.md`：注解约定行改为 JSpecify 单一来源；
- `docs/zh/reference/nullability-annotations.md` 与 `docs/en/reference/nullability-annotations.md`：
  重写「混合方案」一节为 JSpecify + NullAway 说明，保留「何时标注」规则；
- `docs/en/migration-v0.2.md`：记录 breaking change——注解包从 javax/checker 换成
  org.jspecify（0.x 阶段允许有充分理由的 breaking change，理由即本文 §1）；
- `docs/en/user-guide.md`：若提及 nullability 约定则同步。

## 7. 验证与提交

1. `mvn spotless:apply`；
2. `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn test` 全绿（含 NullAway ERROR 通过）；
3. `JAVA_HOME=$(/usr/libexec/java_home -v 25) mvn clean verify`（测试 + 打包检查）；
4. 实施代码按 conventional commit（`refactor:`/`build:`）提交并推送；
5. **本方案文档不提交**（design proposal 例外条款），方向拍板后再定去留。

## 8. 风险与缓解

| 风险 | 缓解 |
|---|---|
| NullAway 报错量超预期 | §5 降级路径：先 WARN 后 ERROR |
| 开发者 JDK 不一致（brew openjdk 25.0.2 / Temurin 25.0.4.1 并存） | enforcer `[21,)` 兜底 + 文档写明 `java_home -v 25`；补丁版差异对编译检查无影响 |
| javadoc 插件对 TYPE_USE 注解的渲染 | maven-javadoc-plugin 3.12 支持；`failOnWarnings=false` 已就位 |
| pitest profile | 不经过 Error Prone 路径，无影响；迁移后回归一次 `-Ppitest` 可选 |
| 下游反射读取 `javax.annotation.Nullable` | 概率极低；迁移说明中如实记录 |

## 9. 实施记录（2026-09-28 补充）

方案已全部落地。实施结果与本文的差异：

| 项 | 方案（所在小节） | 实际 |
|---|---|---|
| 依赖版本 | errorprone `2.43.0+`、nullaway `0.13.x`（§3.2） | **errorprone 2.50.0**、**nullaway 0.14.2**（`pom.xml:56-57`） |
| 破坏性变更记录位置 | 计划写入 `docs/en/migration-v0.2.md`（§6） | 实际落在 `docs/en/migration-v0.3.md:679`「Nullability annotations are now JSpecify」——0.3.0 才是引入方，写进 v0.3 说明更准确 |
| 编译参数 | §3.2 的 Error Prone + `NullAway:ERROR` + `OnlyNullMarked=true` | 已按此落地（`pom.xml:135`），另加 `-XepOpt:NullAway:HandleTestAssertionLibraries=true`（方案未提，用于让测试断言库不产生误报） |
| `JSpecifyMode` | 暂不开启（§3.4） | 仍未开启，维持原决定 |
| JDK 门槛 | 新增 enforcer `requireJavaVersion [21,)`（§2） | 已加（`pom.xml:153, 165-168`） |
| 包级 `@NullMarked` | 替换 2 个 `@ParametersAreNonnullByDefault`（§4） | 已替换（`package-info.java:9`、`queue/package-info.java:13`） |
| 文档同步 | `AGENTS.md`、`docs/{en,zh}/reference/nullability-annotations.md` 改写（§6） | 已完成 |

结论：本次迁移的三个目标——注解体系统一到 JSpecify、null 错误在 `mvn compile` 阶段失败、
产物字节码保持 Java 8——均已达成。§8 的降级路径（先 `WARN` 后 `ERROR`）**未被启用**：
`pom.xml:135` 落地即为 `-Xep:NullAway:ERROR`。
