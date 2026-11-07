# parallel-in-scope 旗舰项目评审合集

> 按用户要求归档的分析稿，不是活契约，不约束实现。基线：`dev/v0.4.0` @ `86450971`（2026-10-29）。
> 原始材料来自三路 kimi review、一轮首次使用者体验方案，以及本会话的只读审计。
> 可度量事实以 `06-verified-facts.md` 为准；其余文件保留各评审的原始判断和措辞。

## 一句话结论

三路评审的判断一致：**代码和测试是 senior 级，对外首屏会让人低估项目。** 最值得做的不是改内核，而是用约 2–4 小时把 About、README 首屏、版本矛盾和 CI 覆盖修好。

## 阅读顺序

1. `01-portfolio-review.md`：招聘经理/技术负责人视角，回答“会不会约面、面试怎么讲”。
2. `03-adversarial-review.md`：最强反对意见，回答“哪里会被当场戳破、2 小时先修什么”。
3. `02-engineering-review.md`：工程/架构视角，含实测数据、CI 缺口和 P0/P1。
4. `04-first-use-p0-p1-plan.md`：首次使用者体验的完整方案，含可执行验收标准和防漂移机制。
5. `05-github-about-copy.md`：GitHub About 英文候选、topics、homepage 和 README 三行 pitch。
6. `06-verified-facts.md`：本轮可复核事实、数字口径和冲突纠偏。

## 三路评审的共识

### 真正强的部分

- 内核机制是真的：bind-before-submit、取消传播、结果落定、body 退出、executor 等待图都有实现和测试支撑。
- 测试是拿真线程跑出来的：970 tests / 85 classes，测试/主代码行数约 2.0:1，59/85 个测试类直接起真线程池。
- 契约有边界感：实现主动写明 wait-cycle 报告不是死锁证明，这类克制在个人项目里少见。
- 已有的差异化素材被埋没了：竞态回归测试、API surface 锁、ADR、AI 并发误报案例、ownership 分层图。

### 最刺眼的问题

- **About 过度声明**：当前 GitHub description 是中文，并写了“死锁检测”，与实现里的 “not a deadlock proof” 冲突。
- **README 首屏像 API 参考，不像问题陈述**：重型示例在前，痛点、选型理由和验证证据在后。
- **CI 不覆盖展示中的代码**：`ci.yml` / `codeql.yml` 只监听 `main`，而开发直接 push `dev/v0.4.0`。
- **对外数字过期**：流传的 1062 tests / 106 classes 来自旧 Surefire 报告；当前实测是 970 / 85。
- **版本叙事自相矛盾**：README 说最新稳定版 0.3.0，却又保留 “stable 0.2.0 line” 引导；中文 README 文档表缺 v0.4 行。
- **缺少公开的保证与限制页**：协作式取消、借用 executor、诊断不是预防、starvation 的判别边界，都散落在长文档里。
- **零 benchmark、无公开 Javadoc 入口、无图/GIF**：技术深度有，但首屏没有可快速核对的证据。

## 三份评审的差异

| 视角 | 结论 | 关注点 |
|---|---|---|
| Portfolio | **会安排 45 分钟面试** | 技术深度足够；但 README 把重型 API 放在问题、差异化和证据之前，明显低卖 |
| Engineering | **会在细分场景认真考虑采用** | 内核和测试可信；但过期数字、开发主线无 CI、零性能数据会先于技术深度被看到 |
| Adversarial | **看门面 reject，看代码 interview** | 最强反对意见；包装改约 3.5 小时，代码不用改 |

三者不矛盾：同一诊断，不同阈值。Portfolio 看“值不值得聊”，Engineering 看“敢不敢用”，Adversarial 看“哪里会被当场问穿”。

## 冲突与纠偏

| 冲突 | 结论 |
|---|---|
| 1062 tests / 106 classes vs 970 / 85 | 以 `mvn clean test` 实测的 **970 / 85** 为准；1062 来自已删除 `TaskGraph*Test` 的旧报告 |
| PIT 是否算通过 | **不算**。scoped PIT 运行中出现 3 次 minion `TIMED_OUT`，按仓库规则记为 blocked；不能当面试证据 |
| “死锁检测” | 不能说 detection/prevention。准确说法是 **thread-pool wait-cycle diagnostics / risk signals, not a deadlock proof** |
| About 用中文还是英文 | 用英文；当前中文 description 对国际访客和英文简历都不利 |
| 是否要造 star / commit / benchmark | 不要。没有真实用户就明确说个人项目；Maven Central + CI 外部消费者已经够用 |

## 建议的落地顺序

按性价比排序，详细论证见 `04-first-use-p0-p1-plan.md`：

1. **About + topics + homepage**（约 10 分钟）：换英文、删“死锁检测”、挂 `https://monadrome.github.io/parallel-in-scope/`。
2. **README 首屏重写**（约 4–6 小时）：问题 → 最小可运行示例 → 三条契约 → 选型理由 → 证据 → 限制。
3. **CI 覆盖开发主线**（约 10 分钟）：`ci.yml` / `codeql.yml` 加 `dev/**`。
4. **修版本矛盾**（约 1 小时）：0.2.0 行、中文 README 缺 v0.4、过期数字。
5. **加图与 40 秒 GIF**（约 1–2 小时）：插入 `docs/assets/ownership-layers.svg`，演示一次失败批次。
6. **补 Javadoc 链接 + `docs/en/limits.md`**（约 2–4 小时）：把“保证/不保证”集中成一页。
7. **Profile README + 置顶项目**（约 1–2 小时）：把 `parallel-in-scope` 放到第一屏。

## 不要做

- 不要伪造 commit、star、用户数、benchmark 或“生产验证”。
- 不要把 wait-cycle diagnostics 写成 deadlock detection / prevention。
- 不要拿 blocked 的 PIT 运行当“变异测试通过”。
- 不要为了 About 的短而牺牲准确性；宁可少写一个能力。
- 不要拆 `queue` 为独立 artifact（ADR-0006 已定边界）。
- 不要在未经用户要求时提交本分析目录；它是审阅输入，不是活文档。

## 原始材料与溯源

| 归档文件 | 原始路径 | 说明 |
|---|---|---|
| `01-portfolio-review.md` | `/Users/linqh/.agent-handoff/h-20261029-145048-52e5/review.md` | 招聘经理视角全文 |
| `02-engineering-review.md` | `/Users/linqh/.agent-handoff/h-20261029-145048-ed3a/review.md` | 工程/架构视角全文 |
| `03-adversarial-review.md` | `/Users/linqh/.agent-handoff/h-20261029-145048-f8a4/review.md` | 对抗性面试官视角全文 |
| `04-first-use-p0-p1-plan.md` | `/Users/linqh/.agent-handoff/h-20261029-145201-8569/result.md` | 首次使用者体验 P0/P1 方案全文 |
| `05-github-about-copy.md` | `/Users/linqh/.agent-handoff/about-copy-2026-10-29.md` | About 英文候选与 topics |
| `06-verified-facts.md` | 本会话只读审计 | 可复核事实、数字口径、冲突纠偏 |

原 review 里的行号是评审时快照；后续改代码后可能漂移，引用机制名优先于行号。
