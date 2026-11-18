# REST 接口清单

依据当前源码中的 Spring MVC Controller 映射整理（2026-11-18）。不以旧 OpenAPI 文件作为唯一依据。

共 **19 个 Controller、150 个 HTTP 方法与路径组合**，包含认证接口的兼容路径。

基础地址示例：`http://localhost:8090`。`{id}` 等表示路径参数。大多数租户业务接口需要 `Authorization: Bearer <token>` 和 `X-Tenant-Id`；用户角色权限仍由服务端校验。登录、Widget、平台 Webhook/OAuth 等接口有各自的身份或签名规则，不可统一当作普通租户接口。

SSE 接口返回 `text/event-stream`。标注 Fixture 的接口用于演示，不代表真实平台连接。审批接口表示内部业务审批，不等同于已执行外部平台操作。

## 登录认证（AdminAuthController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| POST | `/api/auth/login` | 账号密码登录，返回访问令牌、刷新令牌和身份信息 | [login](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L33) |
| POST | `/api/admin/login` | 账号密码登录，返回访问令牌、刷新令牌和身份信息 | [login](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L33) |
| POST | `/api/auth/refresh` | 使用刷新令牌换取新令牌 | [refresh](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L46) |
| POST | `/api/admin/refresh` | 使用刷新令牌换取新令牌 | [refresh](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L46) |
| POST | `/api/auth/logout` | 退出登录并吊销令牌 | [logout](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L57) |
| POST | `/api/admin/logout` | 退出登录并吊销令牌 | [logout](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L57) |
| GET | `/api/auth/me` | 查询当前登录用户、角色及租户权限 | [me](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L67) |
| GET | `/api/admin/me` | 查询当前登录用户、角色及租户权限 | [me](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminAuthController.java#L67) |

## 用户与角色管理（AdminUserController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/admin/users` | 查询用户列表 | [users](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminUserController.java#L27) |
| POST | `/api/admin/users` | 创建用户及角色/租户成员关系 | [create](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminUserController.java#L32) |
| PUT | `/api/admin/users/{userId}/status` | 修改用户状态 | [status](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminUserController.java#L42) |
| PUT | `/api/admin/users/{userId}/memberships` | 替换用户租户成员关系 | [memberships](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminUserController.java#L48) |
| GET | `/api/admin/roles` | 查询角色与权限定义 | [roles](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/admin/controller/AdminUserController.java#L57) |

## Token 用量与费用（BillingController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/billing/usage` | 查询当前租户本月 Token 用量与费用 | [getCurrentMonthUsage](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/BillingController.java#L34) |
| GET | `/api/billing/usage/range` | 查询指定日期范围内 Token 用量与费用 | [getUsageRange](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/BillingController.java#L51) |

## 渠道消息与凭据（ChannelIntegrationController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/channels/messages` | 分页查询渠道消息 | [messages](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ChannelIntegrationController.java#L28) |
| POST | `/api/channels/{accountId}/send` | 通过指定渠道账号发送消息 | [send](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ChannelIntegrationController.java#L36) |
| GET | `/api/channels/{accountId}/health` | 查询渠道账号健康状态 | [health](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ChannelIntegrationController.java#L43) |
| PUT | `/api/channels/accounts/{accountId}/credentials` | 配置企业微信渠道账号凭据 | [configureWechat](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ChannelIntegrationController.java#L48) |

## Agent 聊天（ChatController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| POST | `/api/chat/stream` | 商家侧 Agent SSE 流式聊天，按 intent 开放工具 | [stream](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ChatController.java#L38) |

## 电商业务、评测、Widget 与 Shopify（CommerceController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/customers` | 分页查询客户 | [customers](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L60) |
| GET | `/api/customers/{id}` | 查询客户详情 | [customer](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L67) |
| GET | `/api/orders` | 分页查询订单 | [orders](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L72) |
| GET | `/api/orders/{id}` | 根据数据库 ID 查询订单详情 | [order](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L80) |
| GET | `/api/orders/by-number/{orderNumber}` | 按业务订单号查询订单 | [orderByNumber](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L85) |
| GET | `/api/products` | 分页查询商品 | [products](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L90) |
| GET | `/api/products/{id}` | 查询商品详情 | [product](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L98) |
| POST | `/api/products/reindex` | 将商品标记为待重新索引（不表示已完成向量化） | [reindexProducts](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L103) |
| GET | `/api/escalations` | 分页查询人工升级记录 | [escalations](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L109) |
| POST | `/api/escalations` | 创建人工升级请求 | [createEscalation](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L116) |
| PUT | `/api/escalations/{id}/assign` | 分配人工升级处理人 | [assignEscalation](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L121) |
| PUT | `/api/escalations/{id}/resolve` | 解决人工升级请求 | [resolveEscalation](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L129) |
| GET | `/api/tool-calls` | 分页查询工具调用审计日志 | [toolCalls](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L135) |
| GET | `/api/dashboard/commerce` | 查询电商业务仪表盘 | [dashboard](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L143) |
| GET | `/api/evals` | 查询评测汇总及用例 | [evals](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L148) |
| POST | `/api/evals/run` | 执行 Agent 评测 | [runEvals](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L154) |
| GET | `/api/evals/runs` | 分页查询评测运行 | [evalRuns](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L160) |
| GET | `/api/evals/runs/{runId}` | 查询评测运行详情 | [evalRun](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L166) |
| GET | `/api/evals/gold/cases` | 查询 Gold 标注评测用例 | [goldEvalCases](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L171) |
| POST | `/api/evals/gold/cases` | 创建 Gold 评测用例 | [createGoldEvalCase](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L179) |
| POST | `/api/evals/gold/cases/{sourceCaseId}/copy` | 复制用例为 Gold 草稿 | [copyGoldEvalCase](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L185) |
| POST | `/api/evals/gold/cases/{id}/review` | 审核 Gold 评测用例 | [reviewGoldEvalCase](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L192) |
| POST | `/api/rag/evals/run` | 执行 RAG 评测 | [runRagEvals](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L200) |
| GET | `/api/rag/evals/runs` | 分页查询 RAG 评测运行 | [ragEvalRuns](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L206) |
| GET | `/api/rag/evals/runs/{runId}` | 查询 RAG 评测运行详情 | [ragEvalRun](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L212) |
| POST | `/api/widget/session` | 创建买家 Widget 会话并签发会话令牌 | [widgetSession](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L217) |
| POST | `/api/widget/chat/stream` | 买家 Widget SSE 流式聊天 | [widgetChat](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L222) |
| POST | `/api/integrations/shopify/connect` | 配置 Shopify 店铺连接 | [connectShopify](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L257) |
| GET | `/api/integrations/shopify/install` | 创建 Shopify OAuth 安装授权流程 | [installShopify](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L263) |
| GET | `/api/integrations/shopify/oauth/callback` | 处理 Shopify OAuth 授权回调 | [shopifyOAuthCallback](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L269) |
| POST | `/api/integrations/shopify/sync` | 触发 Shopify 数据同步 | [syncShopify](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L276) |
| GET | `/api/integrations/shopify/jobs` | 分页查询 Shopify 同步任务 | [shopifyJobs](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L282) |
| POST | `/api/integrations/shopify/jobs/{jobId}/retry` | 重试 Shopify 同步任务 | [retryShopifyJob](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L288) |
| GET | `/api/integrations/shopify/webhooks` | 分页查询已接收的 Shopify Webhook | [shopifyWebhooks](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L294) |
| POST | `/api/integrations/shopify/webhooks/{eventId}/replay` | 重放 Shopify Webhook 事件 | [replayShopifyWebhook](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L300) |
| GET | `/api/integrations/shopify/privacy-requests` | 查询 Shopify 隐私合规请求 | [shopifyPrivacyRequests](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L306) |
| POST | `/api/integrations/shopify/bulk` | 启动 Shopify 批量初始同步 | [startShopifyBulk](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L312) |
| GET | `/api/integrations/shopify/bulk` | 查询 Shopify Bulk 操作 | [shopifyBulkOperations](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L318) |
| POST | `/api/integrations/shopify/bulk/{id}/refresh` | 刷新 Shopify Bulk 操作状态 | [refreshShopifyBulk](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L324) |
| POST | `/api/integrations/shopify/bulk/{id}/import` | 导入 Shopify Bulk 结果 | [importShopifyBulk](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L330) |
| POST | `/api/webhooks/shopify` | 接收并校验 Shopify Webhook，处理平台事件 | [shopifyWebhook](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommerceController.java#L336) |

## 客服运营、审批、质检与 SRE（CommercialOpsController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/channels/summary` | 查询渠道统计概览 | [channels](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L30) |
| GET | `/api/channels/accounts` | 查询渠道账号 | [channelAccounts](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L35) |
| GET | `/api/inbox/queues` | 查询收件箱队列统计 | [inboxQueues](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L40) |
| GET | `/api/inbox/items` | 分页查询收件箱会话 | [inboxItems](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L45) |
| GET | `/api/inbox/{conversationUuid}/context` | 查询会话的客服上下文 | [inboxContext](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L52) |
| GET | `/api/tickets` | 分页查询工单 | [tickets](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L57) |
| POST | `/api/tickets/{id}/assign` | 分配工单处理人 | [assignTicket](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L64) |
| POST | `/api/tickets/{id}/resolve` | 解决工单 | [resolveTicket](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L73) |
| POST | `/api/inbox/{conversationUuid}/takeover` | 人工接管会话 | [takeover](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L82) |
| POST | `/api/inbox/{conversationUuid}/reply` | 发送人工回复 | [humanReply](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L91) |
| GET | `/api/sla/summary` | 查询 SLA 统计 | [slaSummary](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L100) |
| GET | `/api/sla/policies` | 查询 SLA 策略 | [slaPolicies](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L105) |
| GET | `/api/macros` | 查询快捷回复模板 | [macros](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L110) |
| GET | `/api/actions` | 查询待审批业务动作 | [actions](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L115) |
| GET | `/api/actions/policies` | 查询高风险动作审批策略 | [actionPolicies](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L122) |
| POST | `/api/actions/{source}/{id}/approve` | 批准业务动作申请 | [approveAction](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L127) |
| POST | `/api/actions/{source}/{id}/reject` | 拒绝业务动作申请 | [rejectAction](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L137) |
| GET | `/api/qa/queue` | 查询质检队列 | [qaQueue](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L147) |
| GET | `/api/qa/summary` | 查询质检统计 | [qaSummary](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L154) |
| POST | `/api/qa/{id}/review` | 提交质检审核结果 | [reviewQa](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L159) |
| GET | `/api/operations/summary` | 查询运营统计 | [operations](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L171) |
| GET | `/api/audit/events` | 分页查询审计事件 | [auditEvents](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L176) |
| GET | `/api/sre/summary` | 查询 SRE 概览 | [sre](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L183) |
| GET | `/api/sre/policies` | 查询 SLO 策略 | [sloPolicies](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L188) |
| GET | `/api/sre/snapshots` | 查询 SLO 指标快照 | [sloSnapshots](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L193) |
| GET | `/api/sre/alerts` | 查询告警事件 | [alertEvents](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L199) |
| POST | `/api/sre/evaluate` | 执行 SRE 指标与告警评估 | [evaluateSre](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L205) |
| POST | `/api/sre/alerts/{id}/acknowledge` | 确认告警 | [acknowledgeAlert](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L212) |
| POST | `/api/sre/alerts/{id}/close` | 关闭告警 | [closeAlert](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L221) |
| GET | `/api/sre/rollouts` | 查询发布计划 | [rollouts](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L230) |
| POST | `/api/sre/rollouts` | 创建发布计划 | [createRollout](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L235) |
| POST | `/api/sre/rollouts/{id}/activate` | 激活发布计划 | [activateRollout](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L241) |
| POST | `/api/sre/rollouts/{id}/rollback` | 回滚发布计划 | [rollbackRollout](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L248) |
| GET | `/api/agent/workflow` | 查询 Agent 工作流定义 | [agentWorkflow](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L255) |
| POST | `/api/agent/plan` | 根据意图生成 Agent 专家计划 | [agentPlan](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L260) |
| GET | `/api/agent/guards` | 查询近期 Agent 幂等保护记录 | [agentGuards](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L265) |
| GET | `/api/security/readiness` | 查询生产就绪检查结果 | [productionReadiness](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L270) |
| GET | `/api/security/roles` | 查询安全角色策略 | [rolePolicies](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L275) |
| GET | `/api/security/retention` | 查询数据保留策略 | [retentionPolicies](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/CommercialOpsController.java#L280) |

## 会话与消息（ConversationController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/conversations` | 分页查询租户会话 | [list](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ConversationController.java#L20) |
| GET | `/api/conversations/{conversationUuid}` | 查询会话详情 | [getByUuid](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ConversationController.java#L29) |
| GET | `/api/conversations/{conversationUuid}/messages` | 查询会话消息历史 | [getMessages](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ConversationController.java#L34) |

## 国内平台演示集成（DomesticIntegrationController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/integrations/domestic/platforms` | 查询国内平台集成状态 | [platforms](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/DomesticIntegrationController.java#L22) |
| POST | `/api/integrations/domestic/douyin/fixture-sync` | 同步抖店 Fixture 演示数据 | [syncDouyinFixture](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/DomesticIntegrationController.java#L27) |
| POST | `/api/integrations/domestic/douyin/fixture-webhook` | 模拟处理抖店 Fixture Webhook（非真实平台回调） | [douyinFixtureWebhook](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/DomesticIntegrationController.java#L33) |

## 健康检查（HealthController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/health` | 应用健康检查 | [health](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/controller/HealthController.java#L12) |

## 知识文档管理（KnowledgeController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/knowledge/docs` | 分页查询知识文档 | [list](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/KnowledgeController.java#L20) |
| GET | `/api/knowledge/docs/{docUuid}` | 查询知识文档详情 | [getByUuid](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/KnowledgeController.java#L29) |
| POST | `/api/knowledge/docs` | 创建知识文档并按服务逻辑进行安全审查和索引 | [create](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/KnowledgeController.java#L34) |
| PUT | `/api/knowledge/docs/{docUuid}` | 更新知识文档 | [update](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/KnowledgeController.java#L40) |
| DELETE | `/api/knowledge/docs/{docUuid}` | 删除知识文档 | [delete](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/KnowledgeController.java#L47) |

## 多语言处理（MultilingualController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| POST | `/api/multilingual/debug` | 调试多语言处理与翻译证据 | [debug](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/MultilingualController.java#L24) |
| POST | `/api/multilingual/detect` | 检测文本语言 | [detect](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/MultilingualController.java#L29) |
| GET | `/api/multilingual/summary` | 查询多语言处理统计 | [summary](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/MultilingualController.java#L34) |
| GET | `/api/multilingual/events` | 分页查询语言处理事件 | [events](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/MultilingualController.java#L39) |
| GET | `/api/multilingual/traces/{traceId}` | 查询指定轨迹的多语言证据 | [trace](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/MultilingualController.java#L45) |

## 可观测性与轨迹（ObservabilityController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/observability/summary` | 查询可观测性统计概览 | [summary](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L19) |
| GET | `/api/observability/failures` | 按类别查询失败统计 | [failures](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L24) |
| GET | `/api/observability/tools` | 查询工具调用统计 | [tools](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L29) |
| GET | `/api/observability/eval-trend` | 查询评测趋势 | [evalTrend](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L34) |
| GET | `/api/observability/rag` | 查询 RAG 检索统计 | [rag](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L39) |
| GET | `/api/observability/traces` | 分页查询执行轨迹 | [traces](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L44) |
| GET | `/api/observability/traces/{traceId}` | 查询执行轨迹详情及步骤 | [trace](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/ObservabilityController.java#L52) |

## 企业微信公开回调（PublicWechatKfController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/public/channels/wechat-kf/{callbackKey}` | 企业微信客服回调地址验证 | [verify](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/PublicWechatKfController.java#L26) |
| POST | `/api/public/channels/wechat-kf/{callbackKey}` | 接收企业微信客服回调事件 | [receive](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/PublicWechatKfController.java#L33) |

## RAG 检索与治理（RagController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| POST | `/api/rag/query/debug` | 调试混合检索、重排、证据和引用链路 | [debug](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L29) |
| GET | `/api/rag/health` | 查询知识库及检索依赖健康状态 | [health](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L34) |
| GET | `/api/rag/chunks/{chunkUuid}/neighbors` | 查询知识分块的相邻片段 | [neighbors](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L39) |
| GET | `/api/rag/datasets` | 查询 RAG 数据集 | [datasets](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L44) |
| POST | `/api/rag/datasets` | 创建 RAG 数据集 | [createDataset](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L49) |
| POST | `/api/rag/datasets/{id}/publish` | 发布 RAG 数据集 | [publishDataset](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L55) |
| GET | `/api/rag/feedback` | 查询 RAG 反馈 | [feedback](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L62) |
| POST | `/api/rag/feedback` | 提交 RAG 反馈 | [submitFeedback](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L68) |
| POST | `/api/rag/feedback/{id}/resolve` | 处理 RAG 反馈 | [resolveFeedback](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L75) |
| GET | `/api/rag/index/releases` | 查询索引发布版本 | [indexReleases](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L83) |
| POST | `/api/rag/index/releases` | 创建索引发布版本 | [createIndexRelease](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L88) |
| POST | `/api/rag/index/releases/{version}/activate` | 激活指定索引版本 | [activateIndex](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L94) |
| POST | `/api/rag/index/releases/rollback` | 回滚当前索引版本 | [rollbackIndex](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L101) |
| GET | `/api/rag/experiments` | 查询 RAG 实验记录 | [experiments](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagController.java#L107) |

## 知识文档安全审核（RagSafetyController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/rag/safety/docs` | 按审核状态、风险等级查询知识文档 | [docs](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagSafetyController.java#L26) |
| POST | `/api/rag/safety/docs/{docUuid}/approve` | 批准知识文档安全审核 | [approve](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagSafetyController.java#L34) |
| POST | `/api/rag/safety/docs/{docUuid}/reject` | 拒绝知识文档安全审核 | [reject](../omni-merchant-knowledge/src/main/java/com/omnimerchant/knowledge/controller/RagSafetyController.java#L42) |

## 系统运行信息（SystemRuntimeController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/system/runtime` | 查询运行模式、演示数据启用状态及种子版本 | [runtime](../omni-merchant-bootstrap/src/main/java/com/omnimerchant/system/SystemRuntimeController.java#L27) |

## 租户管理（TenantController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| GET | `/api/tenants` | 分页查询租户 | [list](../omni-merchant-tenant/src/main/java/com/omnimerchant/tenant/controller/TenantController.java#L28) |
| GET | `/api/tenants/{id}` | 查询租户详情 | [getById](../omni-merchant-tenant/src/main/java/com/omnimerchant/tenant/controller/TenantController.java#L45) |
| POST | `/api/tenants` | 创建租户 | [create](../omni-merchant-tenant/src/main/java/com/omnimerchant/tenant/controller/TenantController.java#L53) |
| PUT | `/api/tenants/{id}` | 修改租户 | [update](../omni-merchant-tenant/src/main/java/com/omnimerchant/tenant/controller/TenantController.java#L61) |
| DELETE | `/api/tenants/{id}` | 逻辑删除租户 | [delete](../omni-merchant-tenant/src/main/java/com/omnimerchant/tenant/controller/TenantController.java#L69) |

## 模型调试（TestChatController）

| 方法 | 路径 | 功能 | 源码 |
|---|---|---|---|
| POST | `/api/test/chat` | 非流式模型调用测试 | [chat](../omni-merchant-agent/src/main/java/com/omnimerchant/agent/controller/TestChatController.java#L36) |

## 框架管理端点

以下由 Spring Boot Actuator 按依赖与配置提供，不计入上述 Controller 数量：

| 方法 | 路径 | 功能 |
|---|---|---|
| GET | `/actuator/health` | 应用和依赖健康状态 |
| GET | `/actuator/info` | 已配置的应用信息 |
| GET | `/actuator/prometheus` | Prometheus 指标采集 |

实际是否暴露及可访问，以运行时依赖、安全配置和 `management.endpoints.web.exposure.include` 为准。
