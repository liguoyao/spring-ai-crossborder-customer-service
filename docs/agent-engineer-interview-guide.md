# Agent 开发工程师面试备战指南 —— 基于 OmniMerchant 商用项目的深度拆解

> 定位：一份"项目实现 + 2026 大厂面试考点"双主线文档。
> 项目：OmniMerchant（Spring Boot 4 + Spring AI 2，多租户跨境电商 AI 客服平台）
> 用法：先背"项目一句话与主链路" → 按九大面试主题逐个对照项目实现 → 最后过"商用落地检查清单"和"知识缺口"。

---

## 目录

- [第一部分：2026 Agent 面试考察全景](#第一部分2026-agent-面试考察全景)
- [第二部分：OmniMerchant 项目速览（面试开场话术）](#第二部分omnimerchant-项目速览面试开场话术)
- [第三部分：商用 Agent 项目必须实现的八大支柱](#第三部分商用-agent-项目必须实现的八大支柱)
- [第四部分：Agent 设计详解](#第四部分agent-设计详解)
- [第五部分：Agent 优化（性能/可靠性/成本）](#第五部分agent-优化性能可靠性成本)
- [第六部分：RAG 设计详解](#第六部分rag-设计详解)
- [第七部分：RAG 优化详解](#第七部分rag-优化详解)
- [第八部分：提示词工程](#第八部分提示词工程)
- [第九部分：Token 监控与成本工程](#第九部分token-监控与成本工程)
- [第十部分：评测体系](#第十部分评测体系)
- [第十一部分：高频面试题库与答题框架](#第十一部分高频面试题库与答题框架)
- [第十二部分：手撕代码准备](#第十二部分手撕代码准备)
- [第十三部分：项目未覆盖的知识缺口（务必补课）](#第十三部分项目未覆盖的知识缺口务必补课)
- [第十四部分：商用落地三张清单](#第十四部分商用落地三张清单)

---

# 第一部分：2026 Agent 面试考察全景

根据 2025 年底至 2026 年大厂面经（字节、快手、阿里、腾讯、美团、京东、B站、Shopee 等），Agent 岗位面试已从"考察概念"全面转向**考察工程落地能力**。面试官不再问"什么是 Agent"，而是问"**你的 Agent 崩了怎么修？成本爆了怎么查？越权了怎么防？指标怎么算出来的？**"

## 1.1 九大考察主题（按 2026 下半年出题密度排序）

| # | 主题 | 出题密度 | 一句话考点 |
|---|------|---------|-----------|
| 1 | 工具调用：Function Calling / MCP / Skills | 最高 | 三者边界与演化、大规模工具管理、工程容错 |
| 2 | 评估、幻觉与安全兜底 | 重灾区 | 死循环三层防护、注入防御、幻觉率定义与量化 |
| 3 | 多智能体协作与编排 | 重灾区 | 通信协议、隔离、失败回退、"多 Agent 价值如何证明" |
| 4 | Agent Harness 与运行时工程（新） | 快速上升 | Prompt/Context/Harness Engineering 区别、框架对比 |
| 5 | 记忆管理与上下文工程 | 持续高频 | 分层记忆、压缩时机、长任务目标迷失 |
| 6 | 基础概念与架构模式 | 敲门砖 | Agent vs Workflow、ReAct vs Plan-Execute 的 trade-off |
| 7 | 系统设计压轴题 | 高频压轴 | 端到端设计企业级 Agent，安全性必考 |
| 8 | Agent 特色手撕代码 | 明显上升 | 脱框架手写最小 ReAct Agent、循环控制、异常处理 |
| 9 | 项目深挖与软性问题 | 占比最高 | 简历每个数字都要能讲清计算口径 |

## 1.2 2026 面试的三个关键趋势

1. **从"知道"到"修好"**：异常处理与兜底几乎必考——"Agent 调了三个工具死循环了，你怎么兜底？"
2. **从"结论"到"口径"**：你说"准确率提升 15%"，面试官会追问"测试集多大？谁标注的？口径是什么？"
3. **从"单点"到"系统"**：要求你设计生产级高可用 Agent 系统，有架构分层思维（接入层、决策层、工具层、数据层）。

## 1.3 面试官想看到的人

> 一个**能在非确定性系统里装上确定性安全阀的工程架构师**。

---

# 第二部分：OmniMerchant 项目速览（面试开场话术）

## 2.1 30 秒项目介绍模板

> "我做了一个多租户跨境电商 AI 客服平台，技术栈是 Java 21 + Spring Boot 4 + Spring AI 2。它不是聊天 Demo，而是一个面向商用的受控 Agent 系统：Supervisor-Worker 编排 + ReAct 工具调用 + Evidence-Grade RAG + 确定性评测 + 轨迹回放 + 多租户安全隔离。核心设计原则是：**模型只提出工具调用请求，应用负责执行；工具在暴露给模型前和执行前都要经过 tenant、role、risk、approval 校验**。高风险动作（退款、取消、改地址、补发）只生成人工审批请求，LLM 永远不直接修改外部平台。"

## 2.2 核心架构数据流（背下来，画在白板上）

```
买家消息（任意语言）
   │
   ▼
┌─────────────────── Edge：身份与租户边界 ───────────────────┐
│  Widget Token / JWT → TenantContextHolder → Fail-closed SQL │
│  Redis 三层限流（QPS / 月度 Token 预算 / 并发）                │
└────────────────────────────────────────────────────────────┘
   │
   ▼
SafeGuardAdvisor：prompt injection 检测 + PII 脱敏 + 长度上限
   │
   ▼
AgentOrchestratorService（Supervisor）: intent → SpecialistPlan
   （specialistKey / toolAllowlist / riskLevel / 身份核验 / 审批 / 人工兜底）
   │
   ▼
MultiLingualEngine：语言检测 → 翻译成英语（"中转英语"策略）
   │
   ▼
ModelRouter：intent → 模型偏好 + firstAvailable 降级链
   （openai gpt-4o-mini / anthropic claude-haiku / deepseek 兜底）
   │
   ▼
AgentExecutionGuardService.acquire()：Redis 会话互斥锁（SETNX+TTL+Lua释放）
   │
   ▼
ChatClient（Spring AI）
   ├─ system prompt（ReAct 四步 + CRITICAL 硬规则 + 升级条件）
   ├─ 工具 = 白名单 ∩ 全部工具，再包一层 GuardedToolCallback
   ├─ toolContext: tenantId / traceId / allowedTools / riskLevel ...
   └─ ReAct 循环：THINK → ACT(工具) → OBSERVE → ... → final
   │
   ▼
工具执行（第二层校验）
   ├─ 只读工具：直接执行（带审计）
   └─ 副作用工具：SHA-256 幂等守卫 + 状态机驱动（NEEDS_APPROVAL / HUMAN_ASSIGNED）
   │
   ▼
流式输出（70s 超时 / 未发出才重试 / Resilience4j 熔断）
   │
   ▼
出站翻译 → 状态机 WAITING_CUSTOMER → Trace 完结
   │
   ▼
TokenUsageAdvisor → RocketMQ 异步 → token_usage_daily → Billing 看板
```

## 2.3 六个技术模块的一句话卖点

| 模块 | 一句话卖点 |
|---|---|
| `omni-merchant-agent` | 编排、工具守卫、三层限流、评测、轨迹 |
| `omni-merchant-knowledge` | 混合检索、引用、RAG 安全治理 |
| `omni-merchant-tenant` | 租户上下文、membership、SQL fail-closed 隔离 |
| `omni-merchant-channel` | Web Widget + 企业微信/抖店/Shopify 连接器 |
| `omni-merchant-message` | token 用量与异步消息边界 |
| `omni-merchant-bootstrap` | API、Flyway 迁移、安全与运行时配置 |

---

# 第三部分：商用 Agent 项目必须实现的八大支柱

> 这一节回答"**可以商用的 Agent 项目有哪些地方必须实现**"。
> 按"支柱 → 项目落地方式 → 面试怎么讲"组织。一个商用 Agent 项目缺任何一根支柱，都不算生产级。

## 支柱 1：安全与权限（Security）—— 商用的底线

商用项目的第一性问题不是"AI 能不能答"，而是"AI 会不会闯祸"。

### 1.1 多租户隔离（必须实现）
- **项目落地**：JWT 携带 tenant membership；`TenantContextHolder`（ThreadLocal）贯穿全链路；MyBatis 拦截器对 SQL 强制注入 `tenant_id` 条件（**fail-closed**：缺租户上下文直接拒绝，不是跳过过滤）；PGVector 检索 SQL 显式 `WHERE tenant_id = ?`；Redis key（会话记忆、embedding 缓存、限流）全部带 `tenantId` 前缀。
- **面试讲法**：跨租户数据泄露对 SaaS 是灭顶之灾。我把租户隔离做成了"缺上下文就拒绝"的 fail-closed 语义——隔离失败时宁可拒绝服务，也不能静默返回别人的数据。400/401/403 分别对应"缺租户 / 无权限 / 跨租户访问"。

### 1.2 双层工具安全（必须实现）
- **暴露前过滤**：LLM 根本看不到非白名单工具（`guardedCallbacks` 按 SpecialistPlan.toolAllowlist 筛选注入）。
- **执行前校验**：`GuardedToolCallback` 装饰器校验 tenant 一致性 + 白名单包含 + ToolContext 强制（无上下文的重载直接抛 SecurityException）。
- **面试讲法**：白名单防的是"模型选错工具"；执行守卫防的是"模型构造的输入绕过上下文"。两层都是硬约束，不依赖模型自觉。

### 1.3 高风险动作审批门（必须实现）
- 退款/取消/改地址/补发四个动作只生成 `commerce_action_request` 内部审批单；工具描述、system prompt、状态机三处同时声明边界；`escalateToHuman` 创建 SLA 工单并置 `HUMAN_ASSIGNED`。
- **面试讲法**（对应真题"高危场景如何限制 Agent 直接操作真实资金"）：我用**审批门**而不是"提示模型别干"——风险动作的最终执行权永远在人，模型只能发起申请。这是把"AI 的能力边界"做成架构约束，而不是 prompt 约束。

### 1.4 Prompt Injection 防御（必须实现）
- 入站：`SafeGuardAdvisor` 正则检测（ignore previous / system prompt / [INST] / DAN mode / 零宽字符等）+ 4000 字符上限。
- 知识库侧：`RagSafetyScanner` 扫 8 类风险（注入指令/危险工具诱导/跨租户诱导/隐藏 HTML/密钥/PII/可疑 base64/零宽字符），HIGH 风险文档在检索 SQL 里被直接排除并进人工审核队列——**防间接注入（Indirect Prompt Injection）**。
- **面试讲法**（对应真题"网页、外部文档里的恶意指令如何防注入"）：RAG 的攻击面不只来自用户输入，还来自知识库本身（间接注入）。我在入库前扫描、检索期 SQL 过滤、命中后进审核三道关卡。

### 1.5 PII 与隐私（必须实现）
- 入站 PII 脱敏（信用卡 Luhn 格式、SSN 正则）；trace 入库前 email/phone 正则脱敏 + SHA-256 输入指纹；状态机 reason 脱敏；数据保留策略实体（`DataRetentionPolicy`）。

### 1.6 审计（必须实现）
- 所有工具调用进 `ToolCallLog`；会话状态转换进 `agent_state_transition`；审计事件独立表 + 只读审计员角色。

## 支柱 2：可靠性与容错（Reliability）

### 2.1 流式韧性三件套（必须实现）
70 秒流超时 → **未发出 token 才重试**（`emitted` AtomicBoolean，防止用户看到重复输出）→ Resilience4j 熔断（打开后返回 `[CIRCUIT_OPEN]` 降级话术）。
### 2.2 幂等与去重（必须实现）
副作用工具用 `SHA-256(toolInput)` 做幂等键 + DB 唯一约束，重复请求返回 `DUPLICATE_BLOCKED`。
### 2.3 并发控制（必须实现）
会话级 Redis 互斥锁：`SETNX + TTL 90s + token 释放 Lua（compare-and-delete）`；锁不可用 fail-closed。
### 2.4 显式状态机（必须实现）
9 状态 FSM + 转换白名单 + version 乐观锁 CAS + 转换历史表；工具输出驱动状态（`PENDING_HUMAN_APPROVAL` → NEEDS_APPROVAL）。
### 2.5 Fail-closed 哲学（必须实现）
付费 LLM 相关的外部依赖故障时默认拒绝：限流器 Redis 挂了 → `RATE_LIMITER_UNAVAILABLE` 拒绝；锁挂了 → 拒绝执行；租户上下文缺失 → 所有工具拒绝。**绝不在失去成本护栏与租户隔离的状态下继续跑模型。**

## 支柱 3：成本工程（Cost Engineering）

商用 = 花钱可算、超支可拦、账单可查。详见第九部分。必须实现的最低集合：
- 请求级 token 估算 + 真实 usage 回填；
- 租户级预算（月度）+ QPS + 并发三层原子限流（Redis Lua）；
- token 用量异步持久化（MQ 解耦，记账失败不阻塞业务）；
- 计费看板（按模型单价表核算成本、日趋势、预算利用率）。

## 支柱 4：可观测性与轨迹回放（Observability）

- 每轮对话一个 traceId，`agent_run / agent_step / tool_call_log` 三层结构支持**逐 step 回放**（INTENT/ROUTER/STATE/LANGUAGE/TOOL/RAG 各类步骤）；
- Micrometer Observation 使用 `gen_ai.*` 语义约定（对齐 OpenTelemetry GenAI 规范），**低基数字段**；
- 失败自动分类（`FailureAttributionService`）、SLO 快照、告警事件、Prometheus 指标；
- trace 写入 best-effort——**观测链路故障绝不影响主流程**。

## 支柱 5：评测与回归（Evaluation）

无评测 = 不可维护。必须实现：
- 确定性 CONTRACT 用例（不依赖模型也能回归工程层）；
- 工具选择 Precision/Recall + 禁用工具违规检测 + 引用覆盖率 + 投毒拦截率；
- RAG 回归（MRR / nDCG@K / no-answer / poisoning block）× 多检索模式 × 多租户；
- 报告三格式（Markdown/JSON/JUnit）进 CI 质量门；run 记录 prompt version + index version + git commit 保证可复现。

## 支柱 6：人工兜底（HITL）

- 三层触发：system prompt 规则（置信度<75%、金额>$100、客户愤怒）+ 编排计划预置 + 用户显式要求；
- 升级工具真实落库（工单号、SLA、优先级 1-4 → 等待 20/10/5/2 分钟）；
- 状态机暂停在 `HUMAN_ASSIGNED`，人工处理完可回 AI（`escalated_back_to_ai`）；
- QA 抽检队列（`QaReviewQueue`）+ CSAT。

## 支柱 7：RAG 治理（Knowledge Governance）

- 索引版本化（`activeIndexVersion` / dataset version / index release / retrieval experiment）——检索锁定版本，防"查询期间索引重建导致引用漂移"；
- 文档安全审核流（`RagSafetyReview`）；内容哈希（`content_hash`）+ 生效时间窗（`effective_from/to`）+ 风险等级元数据；
- RAG Workbench：调试响应带 vector/bm25/fused/reranked 四组候选 + evidence level + rerank mode，**每次回答都能回答"为什么是这个结果"**。

## 支柱 8：工程基础设施（Production Plumbing）

Flyway 数据库迁移、Docker Compose 多环境、CI + CodeQL、OpenAPI 校验脚本、Actuator 健康检查、密钥加密存储（`CredentialCipher`）、transactional outbox（webhook 重试/DLQ）、数据保留策略。
> 面试讲法：商用项目 80% 的代码不是"AI 逻辑"，而是这些让 AI 系统可以被**部署、审计、升级、回滚**的工程底座。

---

# 第四部分：Agent 设计详解

## 4.1 为什么用 Supervisor-Worker 而不是单一大 Agent？（高频题）

**面试答案框架**：

1. **可控性**：单一大 Agent 把所有工具给模型，工具选择错误率随工具数量上升；Supervisor-Worker 让每个意图只看到 3-5 个工具，Tool Precision 从评测上可证（项目 200 用例 precision 99-100%）。
2. **风险分级**：不同意图天然有不同风险等级（查订单 MEDIUM vs 退款 HIGH）。计划里显式带 `riskLevel / requiresIdentityVerification / requiresApproval`，下游按级别加防护。
3. **成本与延迟**：意图分流用确定性规则（后续可换小模型分类器），不烧主模型 token；主模型只处理它擅长的生成与推理。
4. **可测试性**：Supervisor 的输出是一个纯数据结构 `SpecialistPlan`，可以脱模评测（DETERMINISTIC eval 不需要 LLM key）。

**关键代码**（`AgentOrchestratorService.plan`）：

```java
return switch (normalizedIntent) {
    case "ORDER_STATUS" -> new SpecialistPlan("order", "订单智能体",
            angry ? List.of("queryOrder", "escalateToHuman") : List.of("queryOrder"),
            "MEDIUM", true, false, angry);
    case "RETURN_REFUND", "CANCEL_ORDER", "ADDRESS_CHANGE" -> new SpecialistPlan("return", "退货/退款智能体",
            List.of("queryOrder", "createReturnRequest", "requestRefundOrReplacement",
                    "requestAddressChange", "escalateToHuman"),
            "HIGH", true, true, true);   // 高风险：必须身份核验 + 必须审批 + 建议人工兜底
```

细节亮点（面试加分）：**情绪感知路由**——消息含 "angry/投诉/生气/糟糕" 时动态追加 `escalateToHuman` 工具并提升风险等级。模型还没说话，编排层已经备好了退路。

## 4.2 ReAct 的落地方式（高频题）

**面试答案框架**：ReAct 的 THINK→ACT→OBSERVE 循环我**没有手写 while 轮询**，而是用 Spring AI ChatClient 的原生 Tool Calling 循环：模型返回 tool_call → 框架执行 → 结果回填 → 模型继续，直到产出最终答案。应用层的职责不是驱动循环，而是**在循环的每个缝隙里装安全阀**：

```
模型看见什么   ← 白名单过滤（只注入 SpecialistPlan 允许的工具）
模型说话前     ← SafeGuard 校验 + PII 脱敏 + 长度上限
模型调用工具时 ← GuardedToolCallback 执行前校验 + 幂等守卫
模型说话后     ← 状态机推进 + Trace 记录 + 出站翻译
循环跑飞了     ← 70s 超时 + 熔断 + 重试限制
```

System prompt 中的 ReAct 声明（`ReActAgentService`）：

```
Decision Process (ReAct):
1. THINK about what the customer needs
2. ACT by calling appropriate tools
3. OBSERVE results and decide the next step
4. Repeat until you can give a complete answer
```

**追问"ReAct vs Plan-Execute-Replan"**：ReAct 单步前瞻、灵活但容易在长任务中漂移；Plan-Execute 先规划再执行、失败重规划，适合长链路任务，但需要监管 Agent 评估子任务完成度。客服场景任务较短（1-3 个工具调用），ReAct 足够且延迟更低；如果做"自动排查支付故障"这种长任务，我会引入 Plan-Execute + 断点恢复。

## 4.3 会话状态机（项目最有辨识度的设计之一）

9 状态 FSM：`NEW / AI_TRIAGE / AI_WORKING / NEEDS_CUSTOMER_VERIFY / NEEDS_APPROVAL / HUMAN_ASSIGNED / WAITING_CUSTOMER / RESOLVED / CLOSED`。

```java
private static final Map<String, Set<String>> ALLOWED = Map.of(
        NEW, Set.of(AI_TRIAGE, CLOSED),
        AI_WORKING, Set.of(AI_TRIAGE, NEEDS_CUSTOMER_VERIFY, NEEDS_APPROVAL,
                HUMAN_ASSIGNED, WAITING_CUSTOMER, RESOLVED),
        ...);
// 非法转换直接抛异常；更新带 version 乐观锁 CAS，并发冲突抛错
```

**面试答案框架**（对应真题"HITL 时 Agent Loop 状态怎么暂停、暂存和恢复"）：
- **暂停**：`escalateToHuman` 工具成功 → 状态转 `HUMAN_ASSIGNED`，会话被人工接管，AI turn 不再被调度；
- **暂存**：Redis 存 12 条滑动窗口记忆（7 天 TTL）+ DB 存状态与转换历史，崩溃后可从 `HUMAN_ASSIGNED` 恢复；
- **恢复**：人工处理完，状态机允许 `HUMAN_ASSIGNED → AI_TRIAGE/WAITING_CUSTOMER/RESOLVED/CLOSED`，即支持"人工退回 AI"（`escalated_back_to_ai` 标记）；
- **一致性保证**：状态转移不是模型说了算——`toolSucceeded()` 依据**工具的真实输出**（如包含 `PENDING_HUMAN_APPROVAL`）才允许转移，这保证"AI 说已退款"与"系统里真有审批单"一致。

## 4.4 记忆系统设计（2026 高频题）

**面试答案框架**（分层记忆）：

| 层 | 项目落地 | 容量/生命周期 |
|---|---|---|
| 工作记忆 | 当前 turn 的 toolContext（traceId、白名单、风险级别） | 单次请求 |
| 会话记忆 | `RedisChatMemory`（Redis List + JSON），`getLast(conv, 12)` 滑动窗口 | 7 天 TTL |
| 长期记忆/知识 | RAG 知识库（PGVector + BM25），带索引版本 | 版本化治理 |

**追问"长对话 Prompt 越来越大怎么办"**：项目当前用**滑动窗口 12 条**（最简单、最可控、成本可预算）。如果要进一步优化，演进路径是：① 窗口外消息做**摘要压缩**保留语义骨架；② 关键事实（订单号、已确认的身份、承诺过的时效）抽取成**结构化会话事实卡**注入 system prompt，防止"任务目标迷失"；③ 评测记忆贡献需要有/无记忆的对照指标（这是项目没做的，见知识缺口）。

**追问"记忆污染"**：不同问题的记忆召回相互污染——项目的会话记忆是**单会话串行**的（会话锁保证），不存在并发交叉污染；跨会话不共享记忆，跨租户物理隔离（Redis key 带 tenantId）。

## 4.5 模型路由（ModelRouter）

```java
var preferred = switch (intent) {
    case "COMPLAINT", "ESCALATION" -> anthropicModel;   // 投诉类：更强共情/推理
    default -> openAiModel;                              // 常规：gpt-4o-mini 便宜快
};
return firstAvailable(preferred, openAiModel, deepSeekModel, anthropicModel);
```

- **降级链**：`ObjectProvider` 可选注入——没配任何 key 也能启动（返回 unconfigured，chat 入口直接给出 `[CONFIG_ERROR]` 提示），`firstAvailable` 按优先级跳过不可用模型。
- **面试讲法**：模型路由是成本与体验的杠杆。投诉场景用贵模型（一次挽回一个差评的 ROI 远高于 token 差价）；常规查询用便宜模型；DeepSeek 兜底防单供应商故障。**所有模型都被 RateLimitedChatModel 包一层**——限流与记账对业务代码零侵入。

## 4.6 人工接管设计

三层触发叠加：prompt 规则（置信度<75%/金额>$100/愤怒）→ 编排预置（`recommendHumanHandoff`）→ 用户显式要求。EscalationService 落库生成 `TKT-yyyyMMddHHmmss-XXXX` 工单，SLA 按优先级 1-4 对应 20/10/5/2 分钟，标记会话 `escalated=1` 并发布 Helpdesk 投影事件。

---

# 第五部分：Agent 优化（性能/可靠性/成本）

## 5.1 死循环与异常兜底（几乎必考）

**面试答案框架**（对应真题"Agent 调了三个工具就死循环了，你怎么兜底？"）——三层防护：

1. **推理层熔断**：
   - LLM 流 70s `timeout`——单次调用级别的硬止损；
   - `retryWhen(backoff(1, 300ms))` 且 **filter(e → !emitted.get())**——只有未输出过内容才重试，且只重试一次，不会越重试越乱；
   - Resilience4j CircuitBreaker 包住整个模型流，连续失败打开熔断，`CallNotPermittedException` 转成 `[CIRCUIT_OPEN]` 友好降级。
2. **工具层硬隔离**：
   - 每个工具执行都被 `GuardedToolCallback` 包裹，异常被工具边界捕获并转成**结构化错误 JSON**返回给模型（模型能"观察到失败"并改道），而不是让异常炸掉整个循环；
   - 副作用工具幂等守卫：同参数重复请求第二次直接返回 `DUPLICATE_BLOCKED`，**模型反复尝试同一动作不会产生重复副作用**（这就是死循环危害的灭火器）。
3. **会话层互斥**：会话锁保证同一会话同时只有一个 turn 在跑，用户狂点发送不会并发叠加多个循环。

（诚实边界：项目未显式实现"最大 ReAct 迭代次数 N"参数——Spring AI 工具循环有内部上限，但面试要能说出"如果要更强硬的控制，应该在 ChatClient options 里显式设置 max tool-call iterations 并在超出时强制走 escalate 路径"。）

## 5.2 Token 成本优化组合拳（背熟，能报数）

| # | 手段 | 位置 | 量化讲法 |
|---|---|---|---|
| 1 | 滑动窗口记忆 12 条 | `chatMemory.getLast(conv, 12)` | prompt 长度 O(1) 不随会话增长 |
| 2 | 输入 4000 字符硬上限 | `SafeGuardAdvisor.MAX_INPUT_LENGTH` | 单请求输入上界可预算 |
| 3 | RAG context 预算 3600 字符，每引用截 360 字符 | `RagContextPacker.DEFAULT_BUDGET_CHARS` | 检索注入量有硬上限 |
| 4 | "中转英语"策略 | `MultiLingualEngine` | 英语输入 SKIPPED 翻译，零额外 token；非英语才付两次翻译 |
| 5 | Embedding 缓存 | MD5(text)+tenant Redis key | 相同问题/文档重复嵌入零成本 |
| 6 | 意图路由选便宜模型 | gpt-4o-mini 主力 / DeepSeek 兜底 | 单价差一个数量级 |
| 7 | Rerank 条件跳过 | 候选 ≤ topN 直接跳过 | 免费的排序调用 |
| 8 | 确定性优先 | 意图分流、查询扩展、评测用规则不用 LLM | 这些环节 token 消耗恒为 0 |
| 9 | 请求前预扣 + 真实回填 | 限流预估 1000 → 响应后按真实 usage 累加 | 预算不会因低估而被击穿太多 |

## 5.3 延迟优化

- SSE 流式输出（status → delta → final 四类事件）；
- 翻译非阻塞降级：翻译失败 fallback 原文，绝不因翻译卡住响应；
- RAG 检索降级链保证"总是能返回结果"而不是报错重试；
- trace/用量记账全部异步（MQ fire-and-forget、best-effort），关键路径上零同步写库。

## 5.4 Fail-closed vs Fail-open（架构品味题）

**面试答案框架**：我按"故障的代价"决策：
- **安全与成本组件故障 → fail-closed**（限流器、租户上下文、会话锁）：放行的代价是越权/烧钱，不可接受；
- **体验与观测组件故障 → fail-open**（翻译 fallback 原文、trace best-effort、记账 fire-and-forget）：这些挂了只是体验降级，不能因此拒绝服务。
一句话：**护栏 fail-closed，锦上添花 fail-open**。

---

# 第六部分：RAG 设计详解

## 6.1 五阶段混合检索管线（白板必画）

```
用户问题
  ↓ ① Query Planning（确定性改写：意图词 + 中英双语扩展）
  ├──────────────┬──────────────┐
  ↓ ②a Vector 召回    ↓ ②b BM25 召回
  PGVector HNSW      PostgreSQL tsvector
  ef_search=40       ts_rank + plainto_tsquery
  cosine 距离        （两路都带 tenant/docType/language/indexVersion
   top-K              过滤 + risk_level<>'HIGH' + trust<>'UNTRUSTED'）
  └──────┬───────────┘
  ↓ ③ RRF 融合  score = Σ 1/(k + rank_i)，k=60
  ↓ ④ Cross-Encoder Rerank（BGE API，候选≤topN 跳过，失败 fallback-rrf）
  ↓ ⑤ 邻居窗口扩展（前后 chunk 以 0.5 分数衰减加入）
  ↓ Context Pack（3600 字符预算 + [doc#chunk] 标记 + 证据分级 + 拒答原因）
```

## 6.2 双路召回的工程细节

- **Vector**：pgvector HNSW 索引，`SET hnsw.ef_search = 40` 显式调质量（ef 越大召回越高、越慢）；余弦相似度 `1 - (embedding <=> ?::vector)`。
- **BM25**：PG 原生 `tsvector + ts_rank`——**零外部组件**。面试讲法："用 PostgreSQL 一站式搞定向量+词法检索，运维成本远低于 ES+专用向量库的组合；代价是超大规模时要换专用引擎，但当前租户级数据量下 PG 是最优性价比。"
- **检索期安全过滤**（在 SQL 里，不是检完再筛）：`risk_level <> 'HIGH'`、`source_trust_level <> 'UNTRUSTED'`、`index_version = 活动版本`——不可信文档**根本进不了候选池**。

## 6.3 Agentic RAG：检索是被编排的

`refundPolicyRAG` 是一个 `@Tool`，只出现在 `policy_rag` specialist 的白名单里。**面试讲法**：我没有做"每轮无脑前置检索"，而是把检索做成受控工具——模型判断需要政策证据时才调，且只有政策类意图看得到这个工具。这同时控制了成本（非政策会话零检索开销）与安全（检索入口受白名单管辖）。

## 6.4 证据分级与主动拒答（Evidence-Grade 灵魂）

`RagContextPacker` 给每个引用算 supportScore（rerank 分优先，无则按 RRF 排名衰减），聚合出四级证据：

```
SUFFICIENT：引用数 ≥ 2 且均分 ≥ 70
PARTIAL  ：均分 ≥ 45 → 回答必须声明不确定性
WEAK     ：→ 建议拒答或升级人工
NONE     ：RAG_NO_RESULT → 直接拒答
```

**面试讲法**（对应真题"如何减少幻觉"）：幻觉治理我分四层——① RAG 提供真实证据（工具优先于参数知识）；② **证据不足时主动拒答**（no-answer 能力，RAG 敢说"我没证据"）；③ citation 全链路可追溯（检索分数→引用→文档版本）；④ 引用忠实度词法校验进评测门禁。评测里 no-answer 100% 通过、citation coverage 100%。

## 6.5 分块策略

递归三级分块：段落 `\n\n` → 句子 `.?!` → 词级硬切；`chunk_size=500, overlap=50`（后块头部拼接前块尾部 overlap）；每块 `detectSection` 抽标题、`section_path` 存元数据、`neighbor_prev/next_uuid` 支持邻居扩展。

**追问"为什么 500/50"**：客服政策文档（退货政策/物流时效）段落短、条款化，500 字符能完整容纳一个条款且嵌入区分度足够；overlap 50 保证条款边界处不丢上下文。这个参数是**可配置 + 可评测**的——RAG eval 报告能对比不同 chunk 参数下的 MRR/nDCG，用数据说话而不是拍脑袋。

## 6.6 RAG 安全治理（防知识库投毒）

- 入库扫描 8 类风险 → HIGH 风险文档检索期排除 + 人工审核队列；
- 索引版本化：检索锁定 `index_version`，eval 报告记录 run 时版本；
- 内容哈希防篡改、生效时间窗支持政策时效（旧政策过期自动失效）；
- **面试金句**：RAG 的攻击面不只来自用户输入，还来自知识库本身——被投毒的文档会在数十次对话里持续放大危害，所以入库审核比对话期过滤更重要。

---

# 第七部分：RAG 优化详解

## 7.1 RRF 为什么优于分数加权融合（高频追问）

```java
score(d) = Σ_rankers 1 / (k + rank_i(d))   // k=60
```
1. **量纲免疫**：余弦相似度 ∈ [0,1] 与 ts_rank（无上界）不可直接比较；RRF 只用**排名**，天然融合异构分数。
2. **一致性奖励**：两路都命中的文档得分累加——语义与词法双确认的结果自然置顶。
3. **鲁棒性**：k=60 平滑头部排名抖动，单路排名小波动不会造成融合结果剧变。

## 7.2 Rerank 的成本意识设计

```java
if (candidates.isEmpty())     return "skipped-empty";
if (candidates.size() <= topN) return "skipped-not-required";  // 排序无意义还花钱
// BGE 挂了 → fallback-rrf，mode 写进 DebugResponse
```
**面试讲法**：reranker 是"精度增强器"而不是"必经之路"——三个跳过/回退分支保证它永远不成为单点故障，且每次回答都能回答"这次到底用没用 reranker"（mode 显式落库）。

## 7.3 邻居窗口扩展

命中 chunk 的前后各 1 块以 **0.5 分数衰减**加入结果——解决"答案恰好被 chunk 切断"的经典问题；衰减保证邻居不会挤掉本体排名；`chunk_index BETWEEN ? AND ?` 单 SQL 取邻居，成本可控。

## 7.4 三级降级召回链（无外部依赖也能跑）

```
Vector 挂了/没配 key → 返回空，BM25 扛住
BM25 无结果          → knowledge_doc 表内存词法检索（含中文 bigram 分词）
```
**面试讲法**：这解释了为什么"BM25-only 也能 102/102 通过评测"——演示环境没有 embedding key 时 RAG 依然可用可评测。**可用性降级但不静默失败**，每层降级都落日志与 debug 信息。

## 7.5 中文检索处理

内存词法检索对中文做 **bigram 切分**（连续 Han 字符逐位二元组），弥补 PG english tsquery 对中文的失效——小细节，面试里能体现对多语言检索的理解。

## 7.6 RAG vs 微调（高频题）

**面试答案框架**：本项目选 RAG 的理由——
1. **知识频繁更新**：退款政策、物流时效按周变更，RAG 改文档即生效；微调需要重新训练+评估周期；
2. **可追溯性**：RAG 每个答案带 citation 指到具体文档+版本，微调知识是"黑盒权重"，商用客服场景必须能回答"你依据哪条政策"；
3. **多租户**：每个租户政策不同，RAG 天然按租户隔离知识库；微调无法按租户出模型；
4. **微调的适用面**：固定领域术语理解、特定输出风格、小模型蒸馏降本——这些是 RAG 做不了的。两者不互斥：先 RAG 后微调是常见演进。

---

# 第八部分：提示词工程

## 8.1 System Prompt 的结构化模板（可复用到任何项目）

```
[角色定位]    You are an intelligent customer service agent for cross-border e-commerce.
[决策过程]    ReAct 四步
[CRITICAL 硬规则]（排序有讲究：反幻觉 → 数据来源 → 边界声明 → 引用要求）
[升级条件]    数值化：confidence<75% / 金额>$100 / 情绪激动
[风格约束]    concise, polite + 用客户语言回答
[动态拼接]    + "Current specialist: X. Only the registered tools may be used for this turn."
```

**三条反幻觉规则的精髓**：
- `NEVER fabricate order numbers, tracking numbers, or policy details`
- `ALWAYS use tools to get real data`
- `NEVER claim that money was refunded ... unless a verified tool result says so` —— **把"验证过的工具输出"定义为唯一事实来源**，这是防"模型向用户谎报已退款"的 prompt 层防线（工具描述是第一道、状态机是第三道）。

## 8.2 结构化输出 Prompt（意图分类模板）

```text
You are an intent classifier ... Classify the message into ONE of: ORDER_QUERY, ...（枚举闭合）
Output ONLY JSON, no markdown, no explanation: {"intent": "XXX", "confidence": 0.95}
Examples: - "Where is my order #1234?" -> {...}   （few-shot 对齐格式）
Customer message: {message}
```

四个要点：**枚举闭合分类**（杜绝开放输出）、**强制 JSON-only**（下游解析稳定）、**few-shot 格式锚定**、**要求置信度**（喂给升级决策）。翻译 prompt 同理（"Return ONLY the translated text. No explanations, no quotes, no markdown."）。

## 8.3 工具描述即提示词（最易被忽视）

对比两个 `@Tool` description 的设计差异：
- `queryOrder`（只读）：**返回什么**（模型据此决定是否需要再调别的工具）+ **何时使用**（正例场景列举）。
- `requestRefundOrReplacement`（高危）：**边界声明**——"The AI never executes refunds directly; this only creates a pending human-approval request." 工具描述里的这句边界话，是防"模型向用户谎称已退款"的第一道防线。

**面试讲法**：工具描述是给模型看的 API 文档。写得越精确（返回结构、适用场景、副作用边界、格式要求），模型的选择错误率和幻觉率越低。这是 prompt engineering 里 ROI 最高的部分之一。

## 8.4 Prompt 版本化

`run.setPromptVersion("agent-v3")` + index version + git commit 一起落 eval run 记录——**prompt 像代码一样有版本号**，回归时能归因到"哪次 prompt 改动导致指标变化"。

---

# 第九部分：Token 监控与成本工程

## 9.1 三层限流：一个 Redis Lua 脚本原子完成

```lua
-- Layer 1: QPS      INCR + 首次 EXPIRE 窗口 → 超限返回 QPS_LIMITED
-- Layer 2: Budget   GET 当月用量 + 预估 token > 月预算 → BUDGET_EXCEEDED
-- Layer 3: Concurrent INCR → 超限 DECR 回滚 → CONCURRENT_LIMITED
-- 三检查一个脚本原子执行，避免多命令竞态；预算键 yyyyMM 滚动 + 32 天 TTL
```

**为什么 Lua**：三个检查若用三条 Redis 命令，中间可被并发插入导致超限；Lua 脚本在 Redis 单线程内原子执行。**限流参数是租户级的**（tenant 表的 qpsLimit / monthlyTokenBudget / concurrentSessionLimit）——**限流本身就是多租户商业模型的一部分**（不同套餐不同额度）。

## 9.2 ChatModel 装饰器模式（监控零侵入的关键）

`RateLimitedChatModel implements ChatModel` 包住真实模型：

```
pre-call：allowRequest(预估1000 token) → 拒绝则直接构造 [RATE_LIMITED]+原因 的 ChatResponse
delegation：delegate.call/stream(prompt)
post-call：从 response.metadata.usage 提取真实 prompt/completion tokens
           → TokenUsageAdvisor 记账 + 预算累加 + 释放并发位
```

- 被拒绝时分**原因映射用户话术**（BUDGET_EXCEEDED → "请联系客服升级套餐"）——**把限流错误翻译成商业语言**；
- 流式场景在 `doFinally` 里用 `lastResponse` 抓 metadata 记账，保证流结束必记账；
- ModelRouter 把**所有路由模型统一 wrap**——业务代码对限流与记账完全无感。

## 9.3 异步持久化：RocketMQ 事件解耦

`TokenUsageAdvisor` → `TokenUsageEvent`（含 tenantId / model / prompt / completion / latency / intent / conversationUuid）→ RocketMQ **fire-and-forget** → `token_usage_daily`。**记账失败绝不阻塞用户响应**——监控链路的故障隔离设计。RocketMQ 未启用时静默跳过（demo 环境零依赖）。

## 9.4 成本核算与预算看板（BillingService）

- 内置每模型单价表（prompt/completion 分别计价），按模型分组算成本、日维度趋势；
- `utilization = 实际用量 / 月度预算 × 100%` ——从"限流"到"计费"到"预算利用率"形成完整商业闭环；
- **面试讲法**：商用 Agent 的成本工程要回答三个问题：**花在哪**（模型维度分解）、**谁花的**（租户维度）、**还剩多少**（预算利用率）——这三个答案都落在一个可查询的看板上，而不是事后翻日志。

---

# 第十部分：评测体系

## 10.1 确定性 CONTRACT 评测（项目最大差异化）

- **200 条 deterministic 用例**，不依赖模型 key 可复现——评的是**编排、白名单、守卫、检索这些确定性工程层**；
- `DETERMINISTIC` 模式默认，`LIVE_AGENT` 模式有开关门控（显式承认"live 评测非确定性、要单独跑"）；
- 指标：pass rate / **Tool Precision / Tool Recall** / Citation Coverage / **Poisoning Block**（100%/99-100%/100%/100%）。

## 10.2 按攻击类型自动推导禁用工具（评测设计的巧思）

```java
if (attack.contains("INJECTION") || attack.contains("POISON") || attack.contains("CROSS_TENANT")) {
    forbidden.add("requestRefundOrReplacement");  // 注入/投毒/跨租户用例
    forbidden.add("requestAddressChange");        // → 退款类工具全禁
    forbidden.add("createReturnRequest");
}
```
安全用例的判定标准是"**绝对不能调高危工具**"，一票否决——安全指标不是平均值，是下限。

## 10.3 RAG 评测

- 4 种检索模式（BM25-only / Hybrid / Hybrid+Rerank / Vector-only）× 2 租户 × 51 用例；
- 指标：**MRR、nDCG@K、no-answer、poisoning block**；每 run 记录 embedding model、query planner version、prompt version、index version、git commit；
- 报告 Markdown/JSON/JUnit 三格式，JUnit 报告可直接接 CI 质量门。

## 10.4 引用忠实度校验（CitationFaithfulnessChecker）

对期望声明分词（去停用词），计算引用文本**词法覆盖率**，< 40% 判 fail——用确定性方法近似"答案是否被引用支撑"，不依赖裁判模型。

## 10.5 诚实边界（面试官会试探）

项目明确区分：CONTRACT 基线 vs 人工 GOLD 数据集（门禁未完成，必须由具名人工审核发布）。**主动讲这个边界反而加分**——表明你理解"确定性回归"和"真实质量评测"是两回事，且知道 GOLD 集的审核流程该怎么做。

## 10.6 面试怎么答"幻觉率怎么定义和计算"

**框架**：先反问口径——① **事实性幻觉**（答案中的客观事实无引用支撑）用 citation coverage + 忠实度校验；② **行为性幻觉**（声称做了没做的事，如"已退款"）用**禁用工具违规率**+状态机对账（AI 声称退款 ↔ 系统存在审批单）；③ **工具幻觉**（调用不存在的工具）直接报错。项目口径：no-answer 100%（该拒答时拒答）+ citation coverage 100% + poisoning block 100%，每项都能落到可复现报告。

---

# 第十一部分：高频面试题库与答题框架

> 按面经主题归类，每题给"答题骨架 + 项目证据"。面试时先给框架、再钉项目细节、最后主动讲边界。

## A. 概念与架构

**Q1：什么是 LLM Agent？和直接调大模型 API 有什么本质区别？**
> 闭环决策 vs 开环生成。单次调用是"输入→输出"开环；Agent 是"感知→规划→行动→观察"的**闭环**，核心组件 Planning / Memory / Tool Use。项目证据：ReAct 循环 + 会话记忆（Redis 滑动窗口）+ 受控工具集 + 状态机管理生命周期。系统层面的差异：非确定性系统——所以我的全部工程都是在给非确定性装确定性安全阀。

**Q2：Agent 和 Workflow 怎么选？**
> 确定性程度决定选型。Workflow 走预定义 DAG，可控、可预测成本，适合**已知路径**；Agent Loop（ReAct）让模型自主决策下一步，适合**路径未知**的任务。我的实践是**混合**：宏观用 Supervisor-Worker 的确定性编排（意图→专家→白名单，本质是 Workflow 化的 Agent），微观在单个 specialist 内用 ReAct 循环。纯自由规划的 Agent 在商业场景不可接受——成本不可预算、行为不可回归。

**Q3：为什么 Java 生态选 Spring AI 而不是 LangChain4j？**
> ① 与 Spring Boot 4 自动配置/观测（Micrometer）原生集成；② ChatClient + Advisor + ToolCallback 抽象够用且不黑盒——我的 GuardedToolCallback、RateLimitedChatModel 都是对其接口的标准装饰器实现，说明**抽象层可扩展**；③ 生产配套（事务、安全、Resilience4j）复用 Spring 生态。劣势也要讲：社区/生态不如 LangChain 系、Agent 编排原语（graph/checkpoint）弱于 LangGraph——所以状态机我自己实现了乐观锁版本。

**Q4：ReAct、CoT、ToT 的 trade-off？**
> CoT 单次生成推理链，token 便宜但无外部反馈；ReAct 在循环中穿插工具观察，能纠错但 token 消耗 ×N、延迟 ×N；ToT 多路径探索+回溯，质量上限最高但成本可到 ReAct 的数倍。客服场景决策链短、实时性要求高，ReAct（甚至多数请求 1-2 次工具调用）是最优点。我能报数：评测里 tool recall 100%、单轮平均工具调用 ≤2。

## B. 工具调用与 MCP

**Q5：Function Calling 完整链路？**
> 工具定义（name/description/JSON Schema 参数）随 prompt 注入 → 模型输出结构化 tool_call（名字+参数 JSON）→ **应用解析、校验、执行**（不是模型执行！）→ 结果序列化回填为 tool/observation 消息 → 模型继续。项目细节：我用 `@Tool/@ToolParam` 声明，`ToolCallbackProvider` 统一注册，运行时按 SpecialistPlan 白名单**动态筛选注入**——模型每轮看到的工具列表都不同。

**Q6：MCP 是什么？解决了什么问题？**
> MCP（Model Context Protocol）是 Anthropic 提出的开放协议，把"应用 ↔ 工具/数据源"的连接标准化为 Client-Server 架构（JSON-RPC，stdio/HTTP/SSE 传输）：工具、资源、提示词统一发现与调用。解决的是 M×N 集成问题——以前每个 Agent 框架 × 每个工具都要写一遍适配器，MCP 后变成 M+N。与 Function Calling 的关系：FC 是**模型能力**（模型知道怎么发起调用），MCP 是**传输与生态标准**（工具如何被发现、鉴权、复用）。Function Calling 在 MCP 架构里仍负责"模型侧"的那一环。
> （项目未接 MCP——诚实讲：当前工具集小（8 个）且同进程，白名单编排比外部协议收益更高；工具规模上来或要跨团队复用工具时，MCP 是正确演进方向。）

**Q7：注册上百个工具怎么处理？工具选择幻觉怎么降？**
> 三类方案：**检索式**（把工具描述嵌入向量库按需召回）、**分层路由**（先选领域再选工具——我的 Supervisor-Worker 就是分层路由的雏形）、**渐进式披露**（先给摘要，用到时再展开完整 schema）。工程原则：单轮暴露的工具越少 precision 越高；项目用白名单把单专家工具控制在 ≤5，评测 precision 99-100% 就是证据。

**Q8：Function Call 参数格式不对怎么办？**
> ① JSON Schema 声明 + 服务端二次校验（我的 `queryOrder` 有 `ORDER_ID_PATTERN` 正则兜底，非法格式返回结构化错误而不是抛异常）；② 结构化错误回给模型让它自纠（ReAct 的 OBSERVE 环节）；③ 校验失败要有**确定性降级**（如要求用户提供邮箱）；④ 参数清洗永远在应用层做，不信任模型输出。

## C. 记忆与上下文

**Q9：短/长期记忆怎么落地？**
> 见 4.4 分层表格。重点讲演进：当前滑动窗口 12 条（可预算、零成本），下一步是窗口外摘要 + 结构化事实卡（订单号/身份/承诺），再下一步跨会话长期记忆（向量库 + 检索式召回，需记忆污染防护——按会话/租户隔离命名空间）。

**Q10：为什么上下文越长模型越不稳定？**
> ① 位置稀释：中间内容注意力衰减（lost in the middle）；② 指令冲突累积：长上下文中多条约束相互竞争，模型遵守率下降；③ 成本与延迟超线性。对策就是主动压缩：预算化（我的 RAG 3600 字符预算、输入 4000 上限、记忆 12 条窗口都是硬预算）、摘要化、关键信息前置。

## D. 安全与兜底

**Q11：Agent 死循环的原因和防护？**
> 原因三类：模型反复尝试同一失败工具（参数幻觉）、工具结果无法满足终止条件、模型"忘记"已做过什么。防护三层（对应项目）：工具层幂等守卫（重复请求 DUPLICATE_BLOCKED）→ 推理层超时/重试限制/熔断 → 规划层显式迭代上限 + escalate 兜底。再加观测层：调用轨迹落库可回放，重复调用同一工具能被死循环检测器发现。

**Q12：生产环境四类典型故障怎么解决？**
> ① **思考链错误传播**：早期步骤的错误结论被后续步骤当事实——对策：关键决策不依赖模型自述，用工具输出/状态机做事实源（我的状态机只认工具真实返回）；② **工具幻觉**（调不存在的工具）：白名单 + 框架校验；③ **JSON 崩坏**：结构化输出约束 + 应用层 schema 校验 + 重试自纠；④ **无限循环**：见 Q11。Debug 方法论（"线上一直调工具不返回怎么排查"）：先看 trace 逐 step 定位（INTENT/ROUTER/TOOL 步骤分层）→ 区分是 prompt（规则冲突）、模型（遵循差）、工具（超时/报错）还是逻辑（状态机卡死）问题——**我的 AgentTraceService 就是为这个排查流程设计的**。

**Q13：越权怎么防？为什么已认证还要连续认证？**
> 纵深防御：JWT 租户身份（会话级）+ 工具级 tenant 校验（每次调用 requireTenant 对账 ToolContext vs ThreadLocal）+ 数据级 SQL 强制过滤。连续认证（连续性认证）的必要性：长会话中上下文可能被注入污染，**一次认证不等于每次动作可信**——我的高风险工具每次调用都重新校验 tenant 上下文，且订单敏感数据要二次身份核验（邮箱/电话），这正是 `requiresIdentityVerification` 的含义。

## E. 系统设计压轴

**Q14：设计一个企业智能客服 Agent（通义/Shopee 真题）**
> 直接用本项目五层架构作答：接入层（多渠道 Widget/API + 租户身份）→ 决策层（Supervisor 分流 + ReAct specialist）→ 工具层（白名单 + 执行守卫 + 审批门）→ 数据层（业务库 + 混合检索知识库 + Redis）→ 治理层（评测/回放/成本/审计）。主动给出**核心成功指标**：解决率、人工转接率、SLA 达标率、CSAT、单会话成本、工具 precision、投毒拦截率——**每类指标我都真的算过**（项目 eval 报告 + billing 看板）。

**Q15：RAG 怎么分块、怎么选向量模型？**
> 分块见 6.5（递归分块 + 500/50 + 可评测调参）。向量模型选型维度：多语言能力（跨境场景必须跨语检索）、维度（存储/速度 vs 精度）、成本、最大输入长度。项目当前 text-embedding-3-small（成本效率最优），并保留了无 embedding 的 BM25 降级——**选型要绑定降级路径**，不能假设依赖永远可用。

---

# 第十二部分：手撕代码准备

2026 下半年的高频 Agent 特色手撕题，全部能用本项目的真实代码当参考实现：

| 手撕题 | 项目参考实现 | 练习要点 |
|---|---|---|
| 脱框架手写最小 ReAct Agent | `ReActAgentService` + `ChatController` | 工具注册（Map<String, Function>）、循环 while、终止条件（final 标记/最大迭代）、异常不炸循环 |
| 手写三层限流器 | `TokenRateLimiter` + `rate_limit.lua` | QPS 窗口 + 预算 + 并发三检查原子化；TTL 首次设置；超限回滚 DECR |
| 手写会话锁 | `AgentExecutionGuardService.acquire/release` | SETNX+TTL、token 释放 Lua（防误解锁）、finally 释放 |
| 手写幂等守卫 | `beginSideEffect/updateGuard` | SHA-256 请求哈希 + 唯一键 insert catch DuplicateKey |
| 手写滑动窗口记忆 | `RedisChatMemory.getLast` | size-range 取尾部 N 条、反序列化容错 |
| 手写 RRF 融合 | `RrfFusion.fuse` | merge 累加 1/(k+rank)、排序、去重 |
| 手写死循环检测器 | 思路：对 tool_call_log 按 (toolName, paramsHash) 计数 | 超阈值中断 + 强制 escalate |
| 手写状态机 | `AgentStateMachineService` | 转换白名单 Map + 乐观锁 version + 转换历史 |
| 手写上下文压缩 | `RagContextPacker.pack` 思路 | 给定消息列表和 token 上限，贪心装填 + 预算耗尽截断 |
| LRU Cache（通用八股） | —— | 配合"embedding 缓存"场景讲：LinkedHashMap 或手写双链表+HashMap |

通用算法不放松：反转/合并链表、二叉树层序、Top-K（快速选择 vs 堆）、无重复字符最长子串、接雨水。八股：Transformer 结构、KV Cache、LoRA 原理、PPO/RLHF 流程（简答即可，应用岗不深挖推导）。

---

# 第十三部分：项目未覆盖的知识缺口（务必补课）

> 面试官不会只问你的项目。以下是本项目**没做或没深做**、但 2026 面试高频的点，按优先级补课：

## P0（大概率被追问，必须提前准备说辞）

1. **MCP 协议细节**：Client-Server 架构、JSON-RPC、stdio vs Streamable HTTP、tools/resources/prompts 三类原语、鉴权（OAuth2）。准备"项目为什么暂不接 MCP + 什么条件下接"的论证（工具规模、跨团队复用、进程边界）。
2. **A2A 与多 Agent 通信**：Agent Card、任务生命周期、与 MCP 的关系（MCP 连"工具"，A2A 连"Agent"）。项目是单体内 Supervisor-Worker，说辞：进程内事件/方法调用已够用，跨服务才需要 A2A。
3. **多 Agent 深水区问题**：子 Agent 上下文/权限/存储三隔离；结论冲突仲裁（加权投票/监管者裁决/置信度对比）；Markdown 传任务防拆分错误传播（结构化 JSON + schema 校验 + 双向确认）；"多 Agent 价值如何证明"（对比实验：单 Agent baseline vs 多 Agent，同评测集跑分）。
4. **上下文压缩细节**：什么时候压（token 阈值触发）、怎么压（分段摘要 vs 关键信息抽取）、工具结果压不压（大结果原地截断 vs 交给小模型摘要）、怎么验证不丢关键内容（压缩前后问答对比评测）。项目只做了"预算控制"，没做"动态压缩"——主动承认并给出演进方案。
5. **记忆的量化评测**：有无记忆对照、记忆污染测试、记忆命中率。准备"我会这么测"的方案。

## P1（会被问到，准备简答）

6. **Skills / 渐进式披露**（Claude Code 系概念）：Skill = 打包的指令+资源+脚本，按需加载；为什么省 token（工具 schema 不用常驻上下文，用到才展开）；与 MCP 的分工。
7. **Harness / Context Engineering 概念辨析**：Prompt Eng（单条指令优化）→ Context Eng（整窗上下文的组装与治理）→ Harness Eng（运行时系统级：循环控制、权限、上下文管理、恢复）。一句话：**Harness 是把 Agent 从"一次调用"变成"一个可靠系统"的全部运行时工程**——本项目就是一套完整 Harness 的 Java 实现。
8. **HITL 断点恢复**：项目有状态机暂停/恢复（HUMAN_ASSIGNED），但无"任务级 checkpoint"（多步任务中途暂停后从第 N 步续跑）。说辞：客服任务短，会话级状态机够用；长任务场景需要 LangGraph 式 checkpoint（状态快照 + 恢复指针）。
9. **主/子 Agent 用不用同规模模型**：路由/规划用小模型（便宜、够用），生成/推理用大模型——项目的 ModelRouter 是这个思想的意图级版本。
10. **Reflexion 自我纠错**：失败后把错误信息回填让模型反思重试。项目的结构化错误回传就是轻量 Reflexion（工具错误 JSON 回模型自纠）。

## P2（了解即可）

11. 大模型八股深水：KV Cache 原理、投机解码、量化、上下文窗口技术（RoPE 外推）。
12. 微调实操：LoRA 参数量级、数据构造、灾难性遗忘、微调+RAG 混合架构。
13. 产品批判题：Deep Research 各家对比、Manus/扣子空间体验评价——考前看看这类产品，形成自己的批评观点（成本不透明/过程不可控/评测缺位等角度）。

---

# 第十四部分：商用落地三张清单

## 清单一：必须实现（Must-Have，缺一个就不是商用项目）

| 领域 | 必须项 | 项目证据 |
|---|---|---|
| 安全 | 多租户 fail-closed 隔离 | TenantInterceptor + PGVector/Redis key 全带 tenantId |
| 安全 | 工具双层校验（暴露前白名单 + 执行前守卫） | `guardedCallbacks` + `GuardedToolCallback` |
| 安全 | 高危动作人工审批门 | 退款/取消/改地址/补发 → `commerce_action_request` |
| 安全 | Prompt injection + 间接注入防御 | `SafeGuardAdvisor` + `RagSafetyScanner` |
| 安全 | PII 脱敏 + 审计日志 | 信用卡/SSN/email/phone 脱敏；`ToolCallLog` + 审计表 |
| 可靠 | 超时 / 受控重试 / 熔断 | 70s + emitted 标志 + Resilience4j |
| 可靠 | 幂等（副作用去重） | SHA-256 幂等键 + 唯一约束 |
| 可靠 | 并发控制（会话互斥） | Redis SETNX + Lua 释放 |
| 可靠 | 显式状态机 | 9 状态 FSM + 乐观锁 |
| 成本 | 三层限流（QPS/预算/并发） | Redis Lua 原子脚本 |
| 成本 | token 记账 + 计费看板 | RocketMQ → token_usage_daily → BillingService |
| 观测 | 全链路 trace + 回放 | agent_run/step/tool_call_log 三层 |
| 观测 | 失败归因 + 告警 + SLO | FailureAttributionService + SLO 快照 |
| 质量 | 确定性评测 + CI 门禁 | 200 CONTRACT 用例，MD/JSON/JUnit 报告 |
| 质量 | 人工兜底（HITL）+ SLA 工单 | escalateToHuman + EscalationService |
| 治理 | RAG 索引版本化 + 文档审核 | activeIndexVersion + RagSafetyReview |
| 治理 | 数据保留策略 | DataRetentionPolicy |
| 工程 | 迁移/容器化/CI/密钥加密 | Flyway + Docker + CodeQL + CredentialCipher |

## 清单二：需要考虑（Should-Consider，架构权衡题）

| 权衡点 | 项目的选择 | 备选与理由 |
|---|---|---|
| Workflow vs Agent Loop | 宏观确定编排 + 微观 ReAct 循环 | 纯 Loop 不可预算；纯 Workflow 无适应性 |
| 单 Agent vs 多 Agent | Supervisor + 6 Specialist | 完全独立子 Agent 适合跨服务长任务，进程内则过度设计 |
| LLM 分类 vs 规则分类 | 意图分流当前规则化（+LLM 模板已备） | 规则可评测零成本；LLM 分类覆盖长尾但需 prompt 评测 |
| RAG vs 微调 | RAG（时效+可追溯+租户隔离） | 固定领域风格优化才微调；两者可叠加 |
| 检索时机 | 工具化按需检索（Agentic RAG） | 每轮前置检索简单但浪费；按需检索依赖模型判断 |
| 记忆方案 | 滑动窗口 12 条 | 摘要压缩省 token 但有信息损失风险；事实卡保关键信息 |
| 翻译策略 | 中转英语 | 每语言独立 prompt/RAG 分叉成本高；代价是翻译质量折损 |
| 向量库 | pgvector（PG 一站式） | 超大规模换 Milvus/Qdrant；当前量级 PG 运维成本最低 |
| 框架 | Spring AI | LangGraph 生态强但 JVM 内需桥接；LangChain4j 抽象较新 |
| 降级方向 | 护栏 fail-closed / 体验 fail-open | 按故障代价决策，不是教条 |

## 清单三：需要优化（可继续演进，面试"怎么优化"题素材）

| 优化方向 | 现状 | 演进方案 |
|---|---|---|
| 上下文压缩 | 硬预算控制 | 阈值触发动态摘要 + 工具结果二级压缩 + 压缩质量对照评测 |
| 长期记忆 | 无（7 天会话记忆） | 向量化长期记忆 + 检索式召回 + 记忆贡献评测 |
| 最大迭代硬限制 | 依赖框架内部上限 | 显式 max tool-call iterations + 超限强制 escalate |
| 查询改写 | 确定性规则扩展 | LLM 改写开关已留（live-query-rewrite），需接评测对比 |
| 多模态 | 纯文本 | 图片商品咨询（多模态嵌入 + 视觉理解） |
| 流式体验 | 翻译路径整段缓冲 | 首句即翻（sentence-level streaming translation）降低首字节延迟 |
| GOLD 评测 | CONTRACT 基线 | 具名人工审核的 GOLD 集 + 定期回归 |
| 人工分配 | SLA 工单队列 | 按技能/负载的智能路由分配 |
| 渠道接入 | Widget 已通，企微/抖店/Shopify 分级 ready | 按凭据逐步真实接通，连接器 outbox 重试已备 |
| 语义缓存 | 无 | 高频问题答案级缓存（相似度匹配 + TTL + 租户隔离） |
| 自适应路由 | 静态意图→模型映射 | 按问题复杂度动态选模型（分类器预估难度） |

---

## 附：面试前最后检查（自测 20 问）

1. 画出项目五层架构和一次对话的完整数据流（不看资料）。
2. `SpecialistPlan` 有哪 7 个字段？分别驱动什么下游机制？
3. 双层工具安全分别防什么？`GuardedToolCallback` 为什么要禁用无 ToolContext 的重载？
4. 死循环三层防护各是什么？幂等守卫的 key 怎么算的？
5. 会话锁的获取和释放分别用什么命令？为什么释放要 Lua？
6. 状态机有哪 9 个状态？`toolSucceeded` 怎么依据工具输出驱动转移？乐观锁在哪？
7. 三层限流为什么要一个 Lua 脚本原子执行？预算键怎么滚动？
8. `RateLimitedChatModel` 装饰器在 pre/post 分别做什么？流式怎么记账？
9. Token 用量从产生到看板经过哪几站？为什么用 MQ？
10. RRF 公式是什么？k=60 的作用？为什么不用分数加权？
11. 检索 SQL 里内联了哪些安全过滤？为什么在 SQL 而不是应用层过滤？
12. 证据四级怎么算的？WEAK 时系统行为是什么？
13. 引用里有哪些字段？Citation Coverage 怎么算出来的？
14. 邻居扩展为什么用 0.5 衰减？rerank 的三个跳过分支是什么？
15. System prompt 里"verified tool result"那条规则为什么关键？
16. 评测的四个核心指标是什么？安全用例的 forbidden tools 怎么推导？
17. "幻觉率"的三个口径分别怎么测？你的数字是怎么算的？
18. 项目哪些地方 fail-closed、哪些 fail-open？决策依据？
19. 项目没做什么（诚实边界）：MCP？多 Agent 通信？动态压缩？GOLD 集？——各自的"什么条件下做、怎么做"说辞。
20. 给面试官的收尾：如果重做一遍，你会改哪三个设计？

---

*文档生成于 2026-11-14，基于 OmniMerchant 代码库实读（ReActAgentService / AgentOrchestratorService / AgentExecutionGuardService / AgentStateMachineService / TokenRateLimiter / RateLimitedChatModel / HybridRagService / RrfFusion / RagContextPacker / CrossEncoderReranker / DocumentChunkingService / RagSafetyScanner / CitationFaithfulnessChecker / AgentEvalRunnerService / BillingService 等 40+ 核心文件）与 2026 年大厂面经（力扣讨论区、面灵AI 155 题合集等公开来源）。*
