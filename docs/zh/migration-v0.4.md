# 迁移到 v0.4

组声明和类型化读取统一使用 `TaskKey<T>`。key 只携带诊断名称，按对象身份判等，
不保存运行期类型令牌。它替代 0.3 的 Class/TypeToken 协议，让正常泛型代码中的声明类型
和查询类型不再漂移。

改前：

```java
runtime.group("user", timeout)
        .par("user", par, User.class, this::loadUser)
        .runAll().resultOf("user", User.class);
```

改后：

```java
TaskKey<User> user = TaskKey.named("user");
ImmediateResult<User> result = runtime.group("user", timeout)
        .par(user, par, this::loadUser)
        .runAll().resultOf(user);
```

- 把字符串加类型的 par/combine 声明改为保存的 key。显式 TaskOptions 仍放在 body 前；
  元组 combine 仍接收聚合值 V。
- 泛型结果直接声明 `TaskKey<List<Order>> orders = TaskKey.named("orders")`。
  已使用早期 0.4 key 草案的代码将 `TaskKey.of(name, type)` 改成 `named(name)`，删除 `type()` 读取。
- 带类型的名称/位置查询改为 `valueOf(key)` 或 `resultOf(key)`，必须复用声明时的原 key。
  同名重建会抛 IllegalArgumentException。
- 动态 `valueOf(name)` / `valueAt(index)` 返回 Object；`resultOf(name)` / `resultAt(index)`
  返回 ImmediateResult<?>。移除带类型参数的查询重载及 typeAt/typeOf。
- 终端按 key 读取成员时使用 `combineValues(terminalKey, par, body)`；其 GroupValues 不含
  终端自己。冻结结果可通过 `resultOf(terminalKey)` 读取终端。
- raw type 和 unchecked cast 造成的类型违规由调用方负责，不再由 raw-class 检查转换为
  USER_FAILURE；后续类型化使用仍可能抛 ClassCastException。用户 body 自抛异常仍归 USER_FAILURE。

key 可跨运行复用，常用 key 可保存为 static final；结果逐次隔离。普通成员和终端之间的
名称仍须唯一。未知 key、重名、null 参数、foreign runtime、过期阶段和跨线程草稿调用仍有
运行期校验。刻意传 null 的 valueOf/resultOf 调用须转成 String 或 TaskKey<T> 以消除重载歧义。

取消、deadline、TTL、终端成功门控和有界清理保持原语义。移除的 API 不提供兼容 shim，
消费方须使用 0.4 重新编译。
