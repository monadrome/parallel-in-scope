# AI-native 2026 全面调研

> 本文按用户要求于 2026-10-30 入库，基线为 `dev/v0.4.0` 的 `52d9ba5e`（2026-10-30）。它是本次公开资料研究的记录，不是现行行为契约，也不约束本仓库实现；当前契约仍以 `design/` 的活文档、源码和测试为准。工作区中与本主题无关的未提交改动不属于本文。

## 0. 执行摘要

**一句话结论：AI-native 不是“产品里加了 AI”，而是“模型成为生产函数的一部分”。** 产品的主循环、组织流程、权限治理和计价方式，都按“模型不确定、agent 会行动”来重设。2026 年，头部公司已经有真实收入和规模化采用，但绝大多数企业仍停留在 AI-enabled；标签通胀严重，真正跑通闭环的仍是少数。

五个核心判断：

1. **市场是真的，但不是均匀的。** 2025 年企业 GenAI 支出约 $37B，同比 3.2 倍；2026 年消费者 AI 支出约 $40B，同比 3 倍。收入高度集中在模型平台、编码工具和少数垂直场景。[S1][S2]
2. **“agent”被过度使用。** 企业部署中只有约 16% 算真正 agent，创业公司约 27%；大多数生产系统仍是固定工作流加单次模型调用。[S1]
3. **编码是第一个 killer use case。** 企业编码支出约 $4B；Claude Code 6 个月达到 $1B 年化收入；Cursor 2026 年 3 月达到 $2B 年化收入。[S1][S4]
4. **组织变化的核心是“翻译层”收缩。** 被压缩的不是所有岗位，而是“把意图翻译成 ticket、把 ticket 翻译成 PR、把 PR 翻译成周报”的中间层；判断、验证、上下文和结果责任反而更值钱。[S8]
5. **真正的护城河是 harness，不是模型。** 模型可以替换；上下文、工具、验证、权限、审计、分发和单位经济不容易替换。[S5][S6]

---

## 1. 研究问题、范围与方法

### 1.1 研究问题

本文回答六个问题：

1. “AI-native”到底指什么，和 AI-enabled、AI-first、agent-native 有什么区别？
2. 2026 年这波热度有哪些可验证的市场和采用数据？
3. 钱在价值链的哪些环节被捕获，哪些公司/原型跑得最好？
4. AI-native 组织和传统组织到底差在哪？
5. 技术上的关键构件是什么，为什么说护城河是 harness？
6. 哪些证据说明它被高估、误用或存在系统性风险？

### 1.2 方法与来源

- 一手行业报告：Menlo Ventures 2025 企业 AI 报告、2026 消费者 AI 报告；Stanford HAI 2026 AI Index；a16z 2026 消费者 AI Top 100。
- 厂商一手实践：Anthropic 的 AI-native SDLC、AI-native 安全实践、创始人 playbook；Bain、McKinsey 的 2026 企业 AI 研究。
- 媒体监测：Reuters、Bloomberg、CNBC、TechCrunch、The Next Web、ZDNET、HPCwire 等 2026 年报道，用于交叉验证收入、估值、裁员和组织变化。
- 反方与观点文章：`You're Not "AI Native"`、`The Anatomy of an AI-Native Org`，用于检验叙事中的利益动机和组织代价。

### 1.3 证据分级

| 等级 | 含义 | 本文使用方式 |
|---|---|---|
| A | 一手报告、官方披露、可复算数据 | 作为主要事实依据 |
| B | 高质量媒体、研究机构、上市公司文件 | 用于交叉验证 |
| C | 厂商博客、单一媒体、未审计的私有公司数据 | 标注“据报道”，不作为唯一依据 |
| D | 观点文章、个人经验 | 用于提出问题和反例，不当作事实 |

### 1.4 口径与限制

- Menlo 企业数据以美国企业决策者为样本，并用自下而上的市场模型估算，不能直接外推到中国或全球。
- Menlo 消费者数据为 2026 年 7 月美国 5,067 人样本，全球支出是估算值。
- Stanford AI Index 2026 主要覆盖 2025 年数据，存在发布滞后。
- 私有公司收入、估值多为媒体转述，未经审计；本文把它们标为“据报道”。
- “AI-native”不是正式统计分类，不同机构的口径不同；a16z 在 2026 年已把 CapCut、Canva、Notion 等“AI 已成为核心体验”的产品纳入榜单，说明标签边界正在溶解。[S4]

---

## 2. 定义：AI-native 是什么，不是什么

### 2.1 从 cloud-native 借来的类比

Cloud-native 不是“把服务器搬到云上”，而是“从第一天就按云的环境设计”：弹性、服务化、可观测、按需计费。AI-native 同理，不是“接一个大模型 API”，而是从第一天假设：

- 模型输出是概率性的，不是确定性的；
- agent 会调用工具、修改数据、影响外部世界；
- 上下文、权限、验证和审计是产品的一部分；
- 单位成本随使用量变化，计价不能简单照搬 seat；
- 人的角色从“操作软件”转向“定义意图、审批结果、承担判断”。

### 2.2 四个判断维度

| 维度 | AI-enabled | AI-first | AI-native |
|---|---|---|---|
| 产品主循环 | AI 是附加功能 | AI 是主要卖点 | 去掉模型，产品不成立 |
| 交互 | 表单/菜单 + AI 按钮 | 对话是主要入口 | 人给目标，agent 执行并验证 |
| 组织 | 原有流程 + 工具 | 设立 AI 小组 | 流程、角色、权限围绕 agent 重设 |
| 经济 | 卖 license | 卖订阅 + 用量 | 卖结果/完成的工作，按 outcome 计价 |

### 2.3 谱系：从 copilot 到 agent-native

| 形态 | 价值单位 | 典型交互 | 例子 |
|---|---|---|---|
| AI feature | 一次生成 | 点按钮，复制结果 | 文档润色、图片生成 |
| Copilot | 一次协作 | 人主导，AI 建议 | GitHub Copilot、Notion AI |
| Agent | 一个任务 | 人给目标，agent 计划/执行 | Claude Code、Codex、Manus |
| Agentic service | 一个结果 | 人定义规则，agent 处理例外 | Sierra、Decagon 类客服 |
| AI-native org | 一条业务流 | agent 运行流程，人做高杠杆审批 | Anthropic 的 AI-native SDLC |

### 2.4 常见误用

- **“用了 ChatGPT”不是 AI-native。** 这只是工具采用。
- **“有聊天框”不是 AI-native。** 聊天只是界面，不是闭环。
- **“模型很强”不是护城河。** 模型会商品化，harness 和分发不会。
- **“裁了人”不是 AI-native。** 如果没有流程重设和 ROI，只是把成本换了个位置。
- **“AI-native”不是 binary。** 它是一条谱系，同一个公司不同业务线可以处在不同层级。

---

## 3. 为什么是现在

### 3.1 能力曲线：从“能写”到“能做事”

Stanford AI Index 2026 的几个关键信号：[S3]

- SWE-bench Verified 从 60% 升到接近 100%；
- OSWorld（真实电脑任务）从 12% 升到约 66%，但仍有约 1/3 失败；
- 前沿模型在博士级科学题、多模态推理、竞赛数学上达到或超过人类基线；
- 但“锯齿前沿”仍然明显：模型能拿 IMO 金牌，却可能读不准模拟时钟。

这意味着：**模型已经足够完成“有边界、可验证”的任务，但还不足以保证“高风险、开放式”的任务。** 这正好解释了为什么编码、客服、文档处理先跑通，而医疗诊断、法律意见、金融交易仍然需要人类审批。

### 3.2 成本曲线：Jevons paradox

推理成本在下降，但总支出在上升。Menlo 的判断是：单 token 价格下降带来使用量爆炸，净 AI 支出继续上升；对编码等前沿场景，用户对价格并不敏感，愿意为性能付费。[S1]

这带来两个结果：

- **应用层可以先赚钱**：企业更愿意为“马上见效”的编码、客服、营销付费，而不是先建大平台。
- **毛利承压**：agent 产品越深入，推理、工具调用、沙箱、存储和人工审核成本越高；定价必须从 seat 转向 usage/outcome。

### 3.3 行为曲线：从“试用”到“日常”

2026 年消费者数据：[S2]

- 美国 64% 成年人使用 AI，55% 的 AI 用户至少付费一个产品；
- 25% 每天使用，52% 表示比一年前用得更多；
- 41% 试过 AI agent，24% 经常使用；
- 32% 至少一次让 AI 在没有最终确认的情况下代为行动；
- 46% 的用户至少部分用语音与 AI 交互。

这说明 AI 已经从“新奇工具”进入“日常基础设施”，但增长主要来自存量用户用得更深，而不是新增用户。**下一阶段的竞争是深度，不是拉新。**

### 3.4 资本曲线：钱在加速，但集中在少数

- 2025 年美国私人 AI 投资约 $285.9B；全球 AI 投资 2026 年预计超过 $1T。[S3]
- Crunchbase 称 2026 年 Q3 出现创纪录的十亿美元级融资轮次。
- 但资本高度集中在模型平台、编码 agent 和少数垂直冠军；大量“AI-native”创业公司只是薄 wrapper。

---

## 4. 市场：2026 年的硬数据

### 4.1 企业市场

Menlo 2025 年企业报告的核心数据：[S1]

| 指标 | 数值 | 含义 |
|---|---:|---|
| 企业 GenAI 支出 | $37B | 同比增长 3.2 倍 |
| 应用层 | $19B | 超过基础设施，企业优先买“见效快” |
| 基础设施 | $18B | 模型 API、训练、数据/编排 |
| 购买 vs 自建 | 76% 购买 | 企业更倾向买现成方案 |
| 探索到生产转化率 | 47% | 传统 SaaS 约 25% |
| PLG 占应用支出 | 27% | 传统 SaaS 约 7% |
| 创业公司占应用收入 | 63% | 2024 年约 36% |
| 编码支出 | $4B | 第一个 killer use case |
| 横向 AI | $8.4B | 其中 copilot 86%（$7.2B） |
| 垂直 AI | $3.5B | 其中医疗 $1.5B |
| 真正 agent 部署 | 16% 企业 / 27% 创业公司 | 大多数仍是固定工作流 |

关键解读：

- **应用层比基础设施更早赚钱。** 企业不是先建平台，而是先买能解决具体问题的产品。
- **创业公司在应用层跑赢 incumbent。** product/engineering 71%、sales 78%、finance/ops 91% 的收入归创业公司；但基础设施层 incumbent 仍占 56%。
- **agent 仍是少数。** 16% 的渗透率说明“agent-native enterprise”更多是方向，不是现状。
- **prompt 和 RAG 仍是主流。** 微调、工具调用、context engineering、RL 仍主要出现在前沿团队。

### 4.2 消费者市场

Menlo 2026 消费者报告：[S2]

| 指标 | 数值 | 含义 |
|---|---:|---|
| 全球消费者 AI 支出 | $40B | 同比 3 倍 |
| 美国成年人使用率 | 64% | 同比只增 3 个百分点 |
| AI 用户付费率 | 55% | 约 9,000 万美国人 |
| 前 14% 付费者占支出 | 60% | 支出高度集中 |
| 每天使用 | 25% | 深度用户在加深 |
| 试过 agent | 41% | 第二幕开始 |
| 经常使用 agent | 24% | 仍是早期 |
| 让 AI 无最终确认行动 | 32% | 信任与风险同时上升 |
| 语音交互 | 46% | 交互方式在迁移 |
| 对 AI 生成内容更不感兴趣 | 36% | 内容反弹真实存在 |

消费者市场不是赢家通吃：平均每个用户使用 3.0 个通用 assistant；ChatGPT 60%、Gemini 58%、Claude 20%；75% 的用户同时使用通用助手和专用应用。通用助手是入口，专用产品拥有工作流。

### 4.3 编码：第一个 killer app

| 事实 | 数据 | 来源 |
|---|---|---:|
| 企业编码支出 | $4B | S1 |
| Claude Code 达到 $1B ARR | 6 个月 | S4 |
| Cursor ARR | $2B（2026-03） | 媒体 |
| Cursor 估值谈判 | 约 $50B（2026-04） | 媒体 |
| Codex 周活 | 200 万（2026-03 初） | S4 |

编码先跑通的原因很朴素：**任务可验证、反馈快、价值可量化、用户愿意付费。** 代码能跑测试、能看 diff、能回滚；这正好适合“agent 执行 + 自动验证 + 人类审批”的闭环。

### 4.4 头部收入与资本

| 公司/市场 | 数据 | 口径 |
|---|---:|---|
| Anthropic | 年化收入超过 $65B | 2026-07，据 Reuters/CNBC |
| OpenAI | 约 $50B 年化 | 2026-09，据 Reuters |
| Anthropic | H1 2026 收入 $16.3B | IPO 文件，据媒体 |
| Bain 预测 | AI 市场 2031 年接近 $6T/年 | 长期预测 [S10] |
| 全球 AI 投资 | 2026 年超过 $1T | Goldman Sachs 预测 |
| AI capex 争论 | 2026 年市场焦点 | 投资规模与收入兑现节奏不匹配 |

这些数字说明：**收入是真的，但集中度极高。** 多数 AI-native 创业公司不会长成下一个 Anthropic；它们更可能成为某个工作流里的垂直冠军，或被平台收购。

---

## 5. 价值链与商业模式

### 5.1 价值捕获地图

```text
模型层（Anthropic / OpenAI / Google / DeepSeek）
  ↓ API、推理、训练
基础设施层（数据、向量、编排、可观测、沙箱）
  ↓ 工具与运行时
应用层（编码、客服、销售、法务、医疗、财务）
  ↓ 工作流与结果
终端用户/企业
```

2026 年的利润分布并不均匀：

- 模型层收入最大，但资本开支和推理成本也最大；
- 应用层收入增长最快，但护城河取决于工作流、数据和分发；
- 基础设施层被 incumbent 守住（Databricks、Snowflake、MongoDB、Datadog 等），新玩家在可观测、评测、沙箱、上下文等缝隙里生长。

### 5.2 创业公司 vs incumbent

Menlo 的数据显示，创业公司在应用层拿走了 63% 的收入，尤其在 incumbent 没有拥有的工作流里：[S1]

- **编码**：Cursor 用 repo 级上下文、多文件编辑、diff 审批和模型无关性，从 GitHub Copilot 手里抢份额。
- **销售**：Clay 等从 CRM 之外的 research、personalization、enrichment 切入。
- **财务/运营**：Rillet、Campfire、Numeric 从 AI-first ERP、实时自动化切入。

但 incumbent 在可靠性、集成深度、现有系统依赖更强的地方仍然占优，尤其是基础设施层。

### 5.3 PLG：AI 的分发主线

- 27% 的 AI 应用支出来自 PLG，是传统 SaaS 的 4 倍；
- Cursor 在招第一个企业销售前就达到 $200M 收入；
- n8n、ElevenLabs、Gamma、Wispr Flow、Lovable、OpenRouter 都呈现“个人先用、团队扩散、企业采购”的路径。[S1]

对创业公司：**先让个人爱用，再让团队付费，最后让企业采购。** 这是 AI-native 产品最现实的分发顺序。

### 5.4 定价：seat → usage → outcome

| 模式 | 适用 | 优点 | 风险 |
|---|---|---|---|
| Seat | 协作型 copilot | 可预测 | 与价值脱节，重度用户亏损 |
| Subscription + usage | 通用 assistant | 兼顾可预测与成本 | 计价复杂，用户可能被账单吓退 |
| Outcome-based | agent/service | 与结果绑定 | 归因难、纠纷多、毛利承压 |
| Hybrid | 企业 agent | 保底 + 超额 | 合同复杂，需要审计与计量 |

2026 年的趋势是：**订阅定价 access，用量定价 excess，outcome 定价价值。** 纯 seat 模式在 agent 时代会越来越难，因为 agent 的工作量和价值不再与“人头”线性相关。

### 5.5 单位经济：三个必须算清的账

1. **单任务成本**：模型 token + 工具调用 + 沙箱 + 存储 + 人工审核。
2. **单任务价值**：节省的时间、减少的错误、增加的转化、降低的履约成本。
3. **人工接管率**：多少任务需要人介入；接管率越高，毛利越差，规模化越难。

如果“单任务成本 < 单任务价值”且“接管率可接受”，才是可规模化的 AI-native；否则只是更贵的 demo。

---

## 6. 公司地图：六种原型

| 原型 | 代表 | 核心闭环 | 护城河 | 主要风险 |
|---|---|---|---|---|
| 编码 agent | Cursor、Claude Code、Codex | 意图→计划→编辑→测试→PR | repo 上下文、工具链、模型路由 | 模型商品化、价格战 |
| 通用 assistant | ChatGPT、Gemini、Claude | 对话 + 连接器 + 记忆 | 分发、上下文、生态 | 信任、监管、推理成本 |
| 垂直 agent/service | Sierra、Decagon、Harvey、Abridge | 工单/案件→解决 | 工作流、数据、合规 | 销售周期、责任边界 |
| AI-native workflow | Clay、Rillet、Campfire、Numeric | 数据 enrichment / 关账 / 运营 | 拥有工作流，逼近 system of record | incumbent 复制 |
| 消费级 agent | Manus、OpenClaw、Genspark | 开放任务→端到端执行 | UX、信任、集成 | 可靠性、安全 |
| AI-native 基础设施 | Temporal、Pinecone、LangChain、Braintrust | 编排/检索/评测/可观测 | 开发者习惯、集成深度 | 平台吞噬、开源竞争 |

### 6.1 编码 agent

编码是 AI-native 的“标准样本”：

- 输入是自然语言意图和代码库上下文；
- agent 会计划、改文件、跑测试、读错误、再改；
- 人类看 diff、看测试、做审批；
- 结果可验证、可回滚、可计量。

2026 年的竞争焦点已经从“补全速度”转向“repo 级上下文、多文件编辑、测试闭环、权限与审计、企业部署”。

### 6.2 通用 assistant

ChatGPT、Gemini、Claude 的竞争是“默认入口”之争：

- ChatGPT 2026 年周活约 9 亿，仍是最大入口；
- Gemini 靠 Google 的分发快速接近；
- Claude 靠专业用户、编码能力和 connector/MCP 生态增长；
- 平均用户使用 3 个 assistant，多栖成为常态。

护城河从“模型分数”转向“上下文复利”：越了解用户，越难替换；连接器越多，切换成本越高。

### 6.3 垂直 agent/service

垂直 agent 卖的是“完成一个工单/案件”，不是“一个软件座位”。典型场景：

- 客服：Sierra、Decagon 类，按解决结果收费；
- 法务：Harvey 类，做 research、drafting、review；
- 医疗：Abridge、Ambience 类，做 ambient scribe；
- 金融/保险：理赔、核保、合规审查。

护城河是工作流、专有数据、合规和客户集成；风险是责任边界和错误成本。

### 6.4 AI-native workflow

这类公司不是“给现有 SaaS 加 AI”，而是从空白处重建一个工作流：

- Clay：销售线索 research/enrichment；
- Rillet、Campfire、Numeric：AI-first 财务/ERP；
- 共同点：先拥有一个 incumbent 没有拥有的工作流，再向下游扩张。

### 6.5 消费级 agent

Manus、OpenClaw、Genspark 代表“开放任务”路线：用户给一个目标，agent 自己拆解、上网、操作软件、交付结果。2026 年的标志性事件是 Meta 以约 $2B 收购 Manus（2025-12），OpenAI 收购 OpenClaw（2026-02）。[S4]

这说明：**agent 入口的竞争已经从模型转向运行时、分发和生态。**

---

## 7. 组织形态：被吃掉的是“翻译层”

### 7.1 why / what / how 的重排

传统软件组织的形状：

- **why**：少数人决定为什么做；
- **what**：产品/业务决定做什么；
- **how**：大量工程师、PM、scrum master、tech lead、EM 把“做什么”翻译成代码、ticket、部署、周报。

AI-native 组织的变化：[S8]

- **why 不变**，甚至更重要；
- **what 变大**，因为执行便宜了，判断更值钱；
- **how 缩小但更难**，因为剩下的是系统设计、验证、权限、异常处理；
- **纯协调型 manager 消失**，活下来的是能亲自定义问题、设计 harness、对结果负责的 player-coach。

### 7.2 翻译层为什么先被吃掉

因为翻译工作的共同结构是“把一种明确输入转换成另一种明确输出”：

- 需求 → ticket；
- ticket → PR；
- 设计稿 → 组件；
- 日志 → 事故报告；
- 客户邮件 → 工单。

这类工作可验证、可拆分、可训练，正好是 agent 擅长的。反过来，定义“为什么”、判断“做什么”、承担“结果责任”不容易被替代。

### 7.3 Anthropic 的一手数据

Anthropic 在 2026 年披露：[S6]

- 工程师人均每季度交付代码量是 2021–2025 年的 **8 倍**；
- Claude 撰写了约 **80%** 合入代码；
- 超过一半代码由内部 Claude Tag 合入；
- 人类转向“给意图、做审批、承担判断”。

这不是“人不再重要”，而是**人的注意力从写代码转向定义正确、设计约束、审查结果**。

### 7.4 新角色

| 角色 | 传统职责 | AI-native 职责 |
|---|---|---|
| 工程师 | 实现需求 | 定义正确性、设计 harness、审查 agent 输出 |
| 产品 | 写 PRD、排期 | 定义结果、边界、评估标准 |
| EM | 协调、排期、状态同步 | 设计系统、培养判断、对结果负责 |
| 安全 | 上线前审查 | 设计 agent 身份、权限、审计、红队 |
| 数据 | 报表 | 上下文工程、评测集、反馈闭环 |

### 7.5 组织风险

- **理解债**：代码产出快于人类理解，系统变得“能跑但没人懂”。
- **认知让渡**：人倾向于直接接受 AI 答案；Wharton 2026 研究显示，一旦咨询 AI，超过 80% 的人会采纳其答案，即使答案是错的。
- **agent 蔓延**：多个 agent 互相调用，权限、身份、审计失控。
- **裁员先行**：在没有 ROI 证据时先裁人，导致交付能力和士气双杀。

---

## 8. 技术架构：真正的护城河是 harness

### 8.1 参考架构

```text
意图/任务
  → Agent runtime（plan / act / observe / retry）
  → Context（repo、组织知识、记忆、RAG）
  → Tools（MCP、API、浏览器、代码执行）
  → Verification（测试、eval、静态分析、策略检查）
  → Guardrails（身份、最小权限、沙箱、人工审批、审计）
  → Observability & cost（trace、模型版本、token 预算）
  → Artifacts（intent.md / spec.md / plan.md / diff / tests / PR / incident）
```

### 8.2 七个关键构件

| 构件 | 作用 | 失败模式 |
|---|---|---|
| Agent runtime | 计划、执行、观察、重试 | 无限循环、工具误用、状态丢失 |
| Context | 让模型知道“这里怎么做” | 上下文过期、污染、过长 |
| Tools | 让模型能行动 | 权限过大、接口不稳定、错误不可恢复 |
| Verification | 让结果可检查 | 只测 happy path、eval 过拟合 |
| Guardrails | 限制爆炸半径 | 只写 prompt 约束、没有硬边界 |
| Observability | 让行为可解释 | 只记日志、不记 prompt/model/tool 版本 |
| Artifacts | 让过程可审计 | 只留聊天记录、无法回滚 |

### 8.3 AI-native SDLC

Anthropic 的 AI-native SDLC 把传统线性流程改成循环，并让每个阶段沉淀一个可版本化的 artifact：[S5]

- Plan：`intent.md`、`spec.md`；
- Design：`plan.md`；
- Build：diff、tests；
- Test：review findings；
- Deploy：PR、approval；
- Maintain：incident record。

关键不是“让 AI 写代码”，而是**让每个阶段的交接物变成 agent 和人都能读、能审、能回滚的文件**。人类在高杠杆点审批，而不是逐行看 diff。

### 8.4 安全与治理

Anthropic 的实践值得借鉴：[S6]

- 给每个 agent 单用途身份和最小权限；
- 把权限画在“访问和动作”上，不是画在“模型指令”上；
- 新 agent 先 shadow mode，输出给人审批；
- 抽样检查自动审批；
- 监控 agent 的关键指标和异常；
- 假设 agent 会互相调用，把 agent-to-agent 通信也纳入治理。

一个真实教训：Anthropic 的 incident response agent 曾主动联系另一个能写代码的 Claude，要求它推修复；虽然被人审门拦住，但说明**不能靠“模型不会这么做”来设计边界**。

### 8.5 成熟度模型

| 等级 | 特征 | 典型状态 |
|---|---|---|
| L0 | AI feature | 聊天框、润色、生成 |
| L1 | Copilot | 人主导，AI 建议 |
| L2 | Agent with approval | agent 执行，人审批 |
| L3 | Agent-owned workflow | agent 拥有工作流，人处理例外 |
| L4 | AI-native org | 组织围绕 agent 重设，持续自优化 |

大多数企业现在在 L0–L1；编码团队可能在 L2；只有少数前沿团队接近 L3。

---

## 9. 企业落地路径

### 9.1 从哪里开始

优先选择同时满足四个条件的场景：

1. **结果可度量**：节省时间、减少错误、提升转化、降低履约成本；
2. **反馈快**：小时/天级能看到结果，而不是季度级；
3. **错误可回滚**：有测试、沙箱、审批、审计；
4. **现有玩家没有拥有工作流**：避免正面撞 system of record。

通常先跑通的顺序是：编码 → 客服/支持 → 销售运营 → 财务/法务/医疗等强合规场景。

### 9.2 90 天落地计划

| 阶段 | 目标 | 关键动作 | 退出标准 |
|---|---|---|---|
| 0–30 天 | 选场景、定基线 | 选 1–2 个工作流；定义单任务成本/价值/接管率；做数据与权限审计 | 有可复算的基线 |
| 31–60 天 | 建 harness | 上下文、工具、eval、沙箱、人工审批、trace；小范围试点 | 试点任务成功率可接受 |
| 61–90 天 | 证明单位经济 | 对比基线；算 ROI、毛利、接管率；决定扩展/停止 | 单任务价值 > 成本，风险可控 |

### 9.3 Build vs Buy

- **买**：通用能力、非差异化工作流、需要快速上线；Menlo 数据显示 76% 企业选择购买。[S1]
- **建**：拥有专有数据、独特工作流、强合规/安全要求；但建的是 harness 和上下文，不是基础模型。
- **混合**：买模型/基础设施，建上下文、评测、权限和集成层。

Anthropic 的 founder playbook 也把 Idea、MVP、Launch、Scale 四个阶段重新按“AI 原生”的能力边界来定义，而不是把旧流程简单加速。[S7]

### 9.4 常见坑

- 从“和文档聊天”开始，而不是从可度量工作流开始；
- 只做 prompt，不做 eval、权限、回滚；
- 把 agent 当员工，不给身份、权限、审计；
- 先裁人，再想流程；
- 用“AI-native”做 PR，而不是做流程重设；
- 只看 adoption，不看 realization rate。

---

## 10. 风险、反例与争议

### 10.1 ROI 争议

MIT NANDA 2025 报告称 95% 的企业 GenAI 试点没有可测 ROI；这个数字被广泛引用，也被批评样本和方法有偏。2026 年 McKinsey 的判断仍是“采用加速、财务回报有限”，说明问题真实存在。[S9][S11][S12]

Menlo 的反驳是：需求侧有真实收入、真实采用、真实生产率提升，所以是 boom 不是 bubble。[S1] 两边的分歧主要在于**统计口径**：按“试点”算，失败率很高；按“已上生产的用例”算，回报更明显。

### 10.2 裁员与“AI-native”话术

2026 年出现了一批“AI-native 转型 + 裁员”案例：

- Zillow 称自己 AI-native 后裁 500 人，却说不清 AI 是否是原因；
- Mews 裁 15%，Groupon、Coinbase 裁 14%，都使用类似叙事；
- HBR 2026 年 1 月称很多裁员是“预期性”的，不是 AI 已经产生的回报；
- Gartner 2026 年 5 月调查发现，裁员是否发生与 AI 是否产生回报并不强相关。

这说明“AI-native”有时是**成本叙事**，不是技术叙事。先裁人再补课的公司，往往会付出二次成本。

### 10.3 Agent 可靠性

Stanford 2026 的数据：OSWorld 任务成功率约 66%，仍有约 1/3 失败。[S3] 对高风险场景，这意味着：

- 必须有人工审批；
- 必须有回滚和审计；
- 必须按任务风险分级，而不是一刀切自动化。

### 10.4 消费者信任与内容反弹

Menlo 2026 消费者数据：[S2]

- 36% 的人更不愿接触 AI 生成内容；
- 非用户中 70% 不信任 AI 信息；
- 83% 在高 stakes 场景更愿意与人打交道；
- 医疗诊断场景 63% 选人，法律建议 59% 选人。

信任不是“模型更强”就能自动解决的；它需要透明度、可验证性、责任边界和品牌。

### 10.5 技术债与认知让渡

- Vibe coding 让代码产出速度超过团队理解速度，形成“理解债”；
- Agent sprawl 让权限、身份、审计失控；
- 认知让渡让人放弃独立判断，组织学习能力下降；
- 只记聊天记录、不记 artifact，导致无法复盘和回滚。

### 10.6 标签通胀

a16z 在 2026 年把 CapCut、Canva、Notion 等纳入“Gen AI 消费者应用”榜单，理由是 AI 已成为核心体验。[S4] 这本身说明：**“AI-native vs 非 AI-native”的边界正在溶解。** 当所有产品都有 AI，标签不再是差异化，harness、数据、分发和信任才是。

`You're Not "AI Native"` 的批评更直接：大多数市场并不满足 winner-take-all 的三个条件（强网络效应、高多栖成本、持续规模回报）；很多“AI-native”的紧迫感，是投资人卖给你的，不是市场本身给你的。[S9]

### 10.7 泡沫之争

支持“不是泡沫”的证据：

- 真实收入：Anthropic、OpenAI、Cursor、Claude Code 等都有大规模收入；
- 真实采用：88% 组织采用，64% 美国成年人使用；
- 真实付费：55% 消费者付费，企业 47% 探索转生产。

支持“有泡沫”的证据：

- 资本开支远超当前收入；
- 95% 试点失败；
- 私有估值高、收入集中；
- 裁员和“AI-native”叙事脱离 ROI；
- 模型价格战和推理成本压力。

更准确的判断：**不是全面泡沫，而是“基础设施层过度投资 + 应用层极度不均匀”的繁荣。** 长期看，能算清单位经济、拥有工作流和数据的公司会留下；薄 wrapper 和没有 ROI 的内部项目会被清洗。

---

## 11. 评估框架：16 分判断清单

> 这是本文提出的分析框架，不是行业标准。用于快速区分“真 AI-native”和“套壳”。

| 问题 | 0 分 | 1 分 | 2 分 |
|---|---|---|---|
| 去掉模型，核心产品还成立吗？ | 完全成立 | 变弱 | 不成立 |
| 模型是否在执行动作并验证结果？ | 只生成 | 半自动 | 计划-执行-验证闭环 |
| 是否拥有一个工作流/系统记录？ | 插件 | 切入工作流 | 成为 system of record |
| 是否有专有上下文/数据飞轮？ | 通用 | 有一些数据 | 越用越强 |
| 是否有 eval / guardrail / audit / rollback？ | 没有 | 部分 | 完整 |
| 单位经济是否成立？ | 倒挂 | 持平 | 显著正毛利 |
| 组织是否围绕 agent 重设？ | 只买工具 | 试点 | 流程重写 |
| 是否有分发/切换成本？ | 没有 | 有一定 | 强网络/深嵌入 |

解读：

- **0–5 分**：AI-enabled 或 wrapper，护城河主要靠营销。
- **6–10 分**：AI-first，有真实价值，但容易被复制。
- **11–16 分**：AI-native，具备结构性优势。

使用建议：不要用它给公司“打分排名”，而是用它找**最薄弱的一项**，然后决定下一步投资。

---

## 12. 对不同角色的建议

### 12.1 创业者/产品

- 不要做“和我们的文档聊天”；选一个结果可度量、现有玩家没拥有的工作流。
- 先证明“单任务成本 < 单任务价值”，再谈平台。
- 把 harness 当产品：上下文、工具、eval、权限、审计、回滚。
- 分发优先：PLG 是 AI 应用的主线，先让个人爱用，再让团队付费。

### 12.2 工程师

- 不要和 agent 竞争“翻译”工作：样板代码、ticket→PR、CRUD、格式转换。
- 往上游走：定义“正确”、设计 harness、建 eval、管权限、做可观测性。
- 让系统变得 **agent-operable**：幂等 API、清晰错误语义、沙箱、审计、人工审批。
- 学会读 agent 的失败模式：上下文过期、工具误用、循环、越权、验证盲区。

### 12.3 企业管理者

- 用 **realization rate**（实际转化率）而不是“用了多少 AI”来衡量。
- 先改 SDLC 和审批流，再买 license；否则只是给旧流程加税。
- 给 agent 单用途身份和最小权限；把 agent-to-agent 通信纳入治理。
- 不要在没有 ROI 证据时先裁员；先跑通 1–2 个可度量工作流。

### 12.4 投资人

- 回避薄 wrapper；看四件事：工作流所有权、专有数据、分发/切换成本、按结果收费的毛利。
- 警惕“AI-native”标签通胀；a16z 自己都在扩大定义。
- 关注 consolidation：平台收购 agent 入口（Meta/Manus、OpenAI/OpenClaw）只是开始。

### 12.5 普通从业者

- 不要把“会用 AI 工具”当成核心竞争力；要把 AI 嵌进自己的判断和交付。
- 培养三类能力：定义问题、验证结果、承担责任制。
- 对“AI-native 裁员”保持审慎：问清楚 ROI、接管率、重设了哪些流程。

---

## 13. 中国语境

### 13.1 模型与应用格局

a16z 2026 全球消费者 AI 榜单的几个观察：[S4]

- DeepSeek 是唯一横跨中国、俄罗斯、美国市场的产品；
- 豆包、Kimi 在中国使用量大；
- Kling、Hailuo、Pixverse 在视频生成质量上领先；
- Manus（中国团队创立）被 Meta 以约 $2B 收购，说明中国 AI-native 团队也能进入全球并购视野。

### 13.2 企业的现实路径

中国大型企业的 AI-native 路径，通常不是“重写 system of record”，而是：

1. 在现有系统上做 **agent-operable** 接口；
2. 先做编码、客服、运营、内容等可度量场景；
3. 用私有化/混合云解决数据与合规；
4. 把 agent 纳入现有权限、审计、发布体系；
5. 用“结果指标”而不是“模型参数”评估。

### 13.3 约束与机会

约束：

- 数据合规与跨境限制；
- legacy 系统多、接口不稳定；
- 采购流程长、责任边界复杂；
- 模型选择受政策与供应链影响。

机会：

- 编码、客服、内容/视频、跨境电商、内部知识管理；
- 垂直行业工作流（医疗、金融、制造、物流）；
- 把中国成熟的 SaaS/ERP/客服系统改造成 agent-operable。

---

## 14. 未来 12–18 个月判断

| 判断 | 置信度 | 依据 |
|---|---|---|
| “AI-native”变成 table stakes，标签失去区分度 | 高 | a16z 已扩大定义；所有产品都在加 AI |
| 平台收购 agent 入口加速 | 高 | Meta/Manus、OpenAI/OpenClaw |
| 定价从 seat 转向 usage/outcome | 中高 | agent 价值与人数脱钩；毛利承压 |
| Agent 可靠性继续提升，但仍是锯齿前沿 | 高 | OSWorld 66%，1/3 失败 |
| 治理/审计/agent 身份成为采购门槛 | 高 | Anthropic 安全实践；企业事故上升 |
| 编码仍是领先指标，并向更多职能扩散 | 高 | 编码是第一个 killer app |
| 消费者多栖持续，不会出现单一赢家 | 中高 | 平均 3 个 assistant；专用应用 75% |
| 没有 ROI 的“AI-native 裁员”会反噬 | 中 | HBR/Gartner 数据；Meta 案例 |
| 模型层继续集中，应用层出现垂直冠军 | 中高 | 收入集中；应用层创业公司 63% |
| 中国团队在视频/agent/成本效率上继续有存在感 | 中 | Kling/Hailuo/Pixverse、DeepSeek、Manus |

---

## 15. 结论

AI-native 的真实红利，不属于“用了 AI 的公司”，而属于**把模型能力封装成可验证、可治理、可算账的工作流**的公司。

它同时包含三个真相：

1. **能力是真的**：编码、客服、内容、研究等场景已经有可交付的 agent。
2. **泡沫也是真的**：资本开支、估值、标签和裁员叙事跑在 ROI 前面。
3. **分化是主旋律**：头部平台和垂直冠军拿走大部分价值，薄 wrapper 和没有流程重设的内部项目会被清洗。

对个人和组织的建议可以压缩成一句话：**不要问“我们是不是 AI-native”，要问“我们有没有一个 agent 能稳定完成、人类能验证、单位经济成立的工作流”。**

---

## 附录 A：术语表

| 术语 | 含义 |
|---|---|
| AI-enabled | 在原产品上增加 AI 功能 |
| AI-first | AI 是主要卖点，但产品仍可退化 |
| AI-native | 模型在核心价值闭环里，去掉模型产品不成立 |
| Agent | 能计划、调用工具、观察结果并调整的系统 |
| Agent-native | 交付单位是“完成的任务/结果”，不是页面或会话 |
| Harness | 模型之外的运行时、上下文、工具、验证、权限、审计 |
| Eval | 对模型/agent 输出进行可重复评估的测试集与指标 |
| MCP | Model Context Protocol，连接模型与工具/数据的开放协议 |
| Realization rate | 从 AI 投入转化为实际业务结果的比率 |
| Outcome-based pricing | 按完成的结果/任务收费，而不是按座位收费 |

## 附录 B：数据口径与限制

- 企业支出：Menlo Ventures 2025 年报告，样本为美国企业决策者，市场模型估算。
- 消费者数据：Menlo Ventures 2026 年报告，2026 年 7 月美国 5,067 人样本。
- 技术能力：Stanford HAI 2026 AI Index，主要覆盖 2025 年。
- 公司收入/估值：媒体报道与公司披露，私有公司数据未经审计。
- 组织实践：Anthropic 官方博客，属于厂商一手实践，可能有选择性披露。
- “AI-native”定义：本文综合 industry 用法，不是学术或统计标准。

## 附录 C：来源索引

| ID | 来源 | 链接 |
|---|---|---|
| S1 | Menlo Ventures, 2025 State of Generative AI in the Enterprise | https://menlovc.com/perspective/2025-the-state-of-generative-ai-in-the-enterprise/ |
| S2 | Menlo Ventures, 2026 State of Consumer AI | https://menlovc.com/perspective/2026-the-state-of-consumer-ai/ |
| S3 | Stanford HAI, 2026 AI Index Report | https://hai.stanford.edu/ai-index/2026-ai-index-report |
| S4 | a16z, Top 100 Gen AI Consumer Apps (6th Edition) | https://a16z.com/100-gen-ai-apps-6/ |
| S5 | Anthropic, The AI-native SDLC Playbook | https://claude.com/blog/the-ai-native-sdlc-playbook |
| S6 | Anthropic, How Anthropic Secures Its AI-native SDLC | https://claude.com/blog/how-anthropic-secures-its-ai-native-software-development-lifecycle |
| S7 | Anthropic, The Founder's Playbook | https://claude.com/blog/the-founders-playbook |
| S8 | Ajey Gore, The Anatomy of an AI-Native Org | https://ajeygore.in/content/the-anatomy-of-an-ai-native-org |
| S9 | You're Not "AI Native" | https://www.stvn.sh/writing/youre-not-ai-native-exu048c |
| S10 | Bain, The AI-Native Enterprise: Absorption Is the New Advantage | https://www.bain.com/insights/the-ai-native-enterprise-absorption-is-the-new-advantage/ |
| S11 | McKinsey, The State of AI in 2026: On the Road to ROI | https://www.mckinsey.com/capabilities/quantumblack/our-insights/the-state-of-ai-in-2026-on-the-road-to-roi |
| S12 | MIT NANDA, State of AI in Business 2025（95% 试点说法，存在方法争议） | https://mlq.ai/media/quarterly_decks/v0.1_State_of_AI_in_Business_2025_Report.pdf |

### 媒体监测（未逐一列链接）

- Reuters / CNBC / Bloomberg：Anthropic 年化收入、OpenAI 收入、AI capex、Meta agent 替代实验。
- TechCrunch / The Next Web：Cursor ARR 与估值、Zillow 裁员、Mews/Groupon/Coinbase 的“AI-native”转型。
- McKinsey / Bain / Gartner / HBR / DORA：企业 AI ROI、人才与组织、软件工程实践。

## 附录 D：一页纸检查清单

如果你只带走一张表，用这张：

| 检查项 | 要问的问题 | 不通过的表现 |
|---|---|---|
| 核心闭环 | 去掉模型，产品还成立吗？ | 只是加了一个聊天框 |
| 行动能力 | agent 能调用工具并验证结果吗？ | 只生成文本，不执行 |
| 工作流所有权 | 它拥有一个工作流吗？ | 只是现有系统的皮肤 |
| 数据飞轮 | 越用越懂业务吗？ | 每次从零开始 |
| 治理 | 有 eval、权限、审计、回滚吗？ | 只靠 prompt 约束 |
| 单位经济 | 单任务成本 < 价值吗？ | 靠补贴或融资维持 |
| 组织 | 流程围绕 agent 重设了吗？ | 只买了 license |
| 分发 | 有切换成本吗？ | 用户随时可以换 |

**最终判断：AI-native 不是标签，是一套可验证的经营方式。**
