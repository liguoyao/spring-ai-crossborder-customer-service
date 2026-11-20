# A2A 物流异常售后协同

可执行请求示例见 [`scripts/a2a.http`](../scripts/a2a.http)，运行顺序、报文字段、SSE 与断点观察方法见 [HTTP 演示指南](a2a-http-demo.md)。

## 运行

技术基线为 A2A 1.0、官方 Java SDK 1.5.0.Final、Java 21、Spring Boot 4.1 和 Spring AI 2.0。客户端使用官方 JSON-RPC transport；MVC 层使用官方消息、任务、Artifact、Agent Card 及序列化器。没有引入 Quarkus 参考服务器。

在项目 `.env` 设置独立的 `A2A_SERVICE_SECRET`（至少 32 字节随机值，不能复用后台 JWT 密钥）。默认 `A2A_ENABLED=false`；demo profile 默认开启并使用明确标记的 FIXTURE 政策判定。Compose 已显式传入相关环境变量。

```powershell
# 在自己的 .env 中配置，勿提交密钥
# A2A_SERVICE_SECRET=<独立随机密钥，至少32字节>
# A2A_ENABLED=true
# A2A_BASE_URL=http://127.0.0.1:8090
.\scripts\demo.ps1
```

单进程通过真实 HTTP 通信。三个端点是 `/api/a2a/order-logistics`、`/api/a2a/policy` 和 `/api/a2a/after-sales`，各自的 Agent Card 位于端点下 `/.well-known/agent-card.json`。这些接口（包括 Card）都需要内部服务令牌；买家和后台 JWT 不可直接调用。

现有 Widget/聊天入口保持不变。演示租户 1001 的物流异常订单可使用：

> 订单 #1003，邮箱 noah@example.com，包裹一直没收到，请申请退款。

应依次出现订单核验、政策核查和内部审批处理。缺少信息时先询问；可在同一会话补充邮箱、订单号或退款/补发选择。金额大于现有 100 阈值、证据不足或系统失败时交接人工。后台「Agent 工作流」和「轨迹回放」可查看真实任务及审批单关联。

FIXTURE 模式仍查询实际租户订单、物流和 RAG 引用；只有证据通过基础门禁后，使用标记为模拟的“允许提交人工审核”决定，不需要模型密钥。它不代表真实政策资格判断，不能作为生产自动决策依据。LIVE 模式由 Spring AI 基于事实和政策引用进行受控判断，没有模型或证据时停止自动申请。

## 通信与任务

Supervisor 从配置白名单地址发现 Agent Card，核对协议和地址，再通过官方 SDK 委派。订单物流提供事实，政策判断必须引用已完成的订单任务；售后必须引用同租户、同会话且相互匹配的订单和政策任务。specialist 无权再次委派。

业务 DataPart v1 包含 `version`、`orderId`、`customerIdentity`、`action`（refund/replacement）、`question`、`orderTaskId`、`policyTaskId`、`traceId`、`parentTaskId`、`deadlineEpochMs`。服务端从签名令牌确定租户、调用方、会话和目标 Agent，不使用正文的租户声明。

MVC 适配支持 SDK 的标准提交、流式提交、查询、取消及订阅方法。任务快照和状态事件使用官方类型，结果通过带版本信息的 Artifact/DataPart 返回。当前不支持 push notification、任务列表等可选能力。

Flyway V25 增加 `a2a_task`、`a2a_message`、`a2a_event`。敏感快照使用 AES-GCM 加密；日志仅记录 trace/task/agent/state 和失败类别。任务按消息标识持久化去重，冲突内容被拒绝；审批复用现有动作幂等保护，审批写入与售后任务完成在同一数据库事务提交。重复请求不会声明创建了新的审批。

等待买家补充信息的任务为 INPUT_REQUIRED，可用原 taskId 和新的 messageId 续办。审批申请创建成功后任务为 COMPLETED，会话为 NEEDS_APPROVAL。取消不撤销已提交申请。执行过程采用行锁保护，正在进行的业务事务完成后才能确认取消结果。

单轮 A2A 总预算为 70 秒；发现、发送、轮询使用同一绝对截止时间。过期后不创建售后申请。worker 与 SSE 消费分别使用 8 线程、32 排队容量的有界线程池。重启时将遗留 SUBMITTED/WORKING 任务标记为失败，INPUT_REQUIRED 保留可续办。

## 配置、观测与边界

- `agent.a2a.enabled`：控制新请求路由与提交；关闭后已有记录仍可查询。
- `agent.a2a.fixture`：仅 demo 配置默认开启，生产使用 false。
- `agent.a2a.base-url`：本地 HTTP 服务地址；通过 `agent.a2a.endpoints.<agentKey>` 可显式配置独立服务地址。不能通过用户消息设置地址。
- `omnimerchant.a2a.requests`：按 agent/outcome 统计完成、失败和重复请求。
- `omnimerchant.a2a.duration`：Agent 执行耗时。
- `/api/agent/a2a/status`、`/api/agent/a2a/tasks?conversationUuid=...`：沿用后台身份与租户权限，只返回配置和当前租户的脱敏记录。

首期按单应用实例拥有任务执行权设计。拆为独立服务前需要分配任务所有权并调整启动恢复逻辑；不能直接让多个实例共同恢复同一批运行任务。服务密钥轮换前应处理或迁移历史加密记录，当前不提供多密钥解密。

## 验证

```powershell
mvn -B -pl omni-merchant-bootstrap -am test
Set-Location omnimerchant-web
npm test
npm run build
```

新增测试覆盖真实 Spring HTTP/SSE、官方 SDK 互通、三个 specialist、Supervisor 发现与委派、并发重复消息、输入续办、取消、重启恢复、开关和跨租户/会话/Agent 访问拒绝。业务测试覆盖身份验证、金额门禁、政策证据、动作选择、审批幂等接入与截止时间。HTTP 集成测试使用 H2 MySQL 模式和模拟工具，不能替代真实 MySQL 及真实模型的部署验收。
