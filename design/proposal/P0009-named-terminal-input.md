# 具名终端输入

## 蒸馏摘要

- 核心出发点：较大异构组插入或调换同类型成员时，位置解构容易读错值。
- 不变量与拟议 MUST：MUST 复用 GroupValues 与同一个 prepared terminal；
  MUST 保留 typed combine，并用不同方法名避免 lambda 重载歧义。
- 被否决方向及理由：名称与类型查询是运行期校验，不能称为增加编译期类型安全；
  没有真实大组消费案例时，少写解构不足以证明新增接口值得。

## 目标与论证

当前三成员汇总：

```java
.combine("profile", cpuPar, Profile.class, values -> buildProfile(
        values.first().first(), values.first().second(), values.second()))
```

可选草图，复用现有 `GroupValues` 和同一个 prepared terminal：

```java
.combineValues("profile", cpuPar, Profile.class, values -> buildProfile(
        values.valueOf("user", User.class),
        values.valueOf("account", Account.class),
        values.valueOf("order", Order.class)))
```

消除插入/调换同类型成员时的位置误取，但名称和类型查询转为运行期校验，不能称为提升
编译期类型安全。采用不同名字避免 lambda 重载歧义；保留 typed combine，无迁移。
这是上轮 E6 的剩余候选，只有真实的大组消费需求足以覆盖新增接口成本时才实施。

主动采用新入口才改变终端输入形状，旧 typed combine 无迁移；body、预算与取消管线不变。
