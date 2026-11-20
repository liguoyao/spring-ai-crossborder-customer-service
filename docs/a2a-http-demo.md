# 使用 .http 观察 A2A 协议

## 1. 准备

示例文件为 [`scripts/a2a.http`](../scripts/a2a.http)，面向 IntelliJ IDEA HTTP Client。预请求脚本使用 IDE 的 [HMAC API](https://www.jetbrains.com/help/idea/http-client-crypto-api-reference.html)，不是 Node.js 脚本。

1. 服务端 `.env` 设置 `A2A_ENABLED=true`、`A2A_SERVICE_SECRET=<独立随机密钥，至少32字节>`，修改后重启后端。演示数据库使用 demo profile。
2. 将 `scripts/http-client.private.env.json.example` 复制为同目录的 `http-client.private.env.json`，填写 `a2aServiceSecret`、`adminEmail`、`adminPassword`。密钥必须与服务端完全一致；该私有文件已加入 Git 忽略。
3. 在 IDEA 打开 `scripts/a2a.http`，环境选择 **a2a-local**。公共地址和租户配置在 `scripts/http-client.env.json`。
4. 按下面顺序逐个点击请求左侧运行按钮，不要一次运行全部请求。需要轮询的步骤要确认状态再继续。

## 2. 先体验完整客服协作

依次运行 **01 → 02 → 03 → 04 → 05 → 06**：

| 请求 | 作用 | 观察点 |
|---|---|---|
| 01 | 创建真实 Widget 会话 | 自动保存 conversationUuid 与 Widget token |
| 02 | 买家提出物流异常退款申请 | 接收买家 SSE 进度；后台 Supervisor 自动发现并调用三个 Agent |
| 03 | 后台登录 | 自动保存后台 token，仅用于管理端观察 |
| 04 | 查询 A2A 配置 | enabled、协议 1.0、SDK 版本、FIXTURE/LIVE |
| 05 | 查看当前会话真实任务 | agent_key、父任务、状态、审批单号；自动提取 traceId |
| 06 | 查看轨迹 | A2A 步骤、耗时、失败类别以及业务证据摘要 |

02 在验证和证据通过后会创建**内部待人工审批申请**，不会执行外部退款。FIXTURE 模式会明确标记模拟政策判断，LIVE 模式缺模型或政策证据时可能转人工。

后台也可以打开「Agent 工作流」，输入 01 返回的会话 UUID 查询；或者在「轨迹回放」打开对应 trace。浏览器 Network 中看到的 02 是买家 SSE；Supervisor → specialist 是后端发出的 HTTP 请求，不会作为三条请求出现在浏览器 Network 中。

## 3. 再看原始 A2A 报文

低层示例只调用订单、政策等读取能力；售后写入请使用上面的完整业务链路，让 Supervisor 初始化会话状态和审批保护。

按 **10 → 11 → 12 → 13 → 14 → 12 → 15 → 16 → 17 → 18** 运行：

- **10** 获取订单 Agent Card，查看 `supportedInterfaces`、`skills`、`capabilities.streaming`、`securitySchemes`。
- **11** 使用 `SendMessage` 提交缺少订单邮箱的任务，保存 `orderTaskId`。
- **12** 重复查询直到 `TASK_STATE_INPUT_REQUIRED`。
- **13** 使用 `SubscribeToTask` 观察该任务的原始 SSE 帧。
- **14** 传入原 taskId，使用新 messageId 补充身份信息；再运行 **12**，直到 `TASK_STATE_COMPLETED`。
- **15–17** 发现政策 Agent，并引用已完成的 orderTaskId 委派政策任务；重复 17 查看证据引用和 `eligible`。
- **18** 使用 `SendStreamingMessage` 新建只读任务，一次观察提交到完成的 SSE 数据。
- **19** 演示取消：重新执行 11、12 得到等待输入的任务，再执行 19。已经完成的任务返回标准 `TaskNotCancelableError`。

当前 SSE 实现会从头重放持久化快照，并在遇到等待输入或终态时关闭。13 应在 14 之前执行；它不是只返回最新状态，也不提供 Last-Event-ID 增量恢复。

每个低层请求都会执行 `scripts/http/a2a-auth.js`：根据 URL 确定目标 Agent，签发绑定 tenant、conversation、audience 的 90 秒服务 JWT，生成新的 JSON-RPC id、messageId 和 70 秒截止时间。因此无需复制长期有效的服务 token。预请求脚本不输出密钥和完整 token。

## 4. 请求和结果如何对应

A2A 在这里没有单独的“建立会话连接”接口。流程是：获取能力声明 → 认证 HTTP 调用 → 创建或续办持久化任务 → 查询或订阅结果。SSE 是观察任务事件的 HTTP 长响应。

以下是 SDK 1.5.0.Final 实际序列化格式的简化示例：

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-001",
  "method": "SendMessage",
  "params": {
    "message": {
      "role": "ROLE_USER",
      "messageId": "message-001",
      "contextId": "你的会话UUID",
      "parts": [{"data": {
        "version": 1,
        "orderId": "#1003",
        "customerIdentity": "noah@example.com",
        "question": "请核查物流。",
        "deadlineEpochMs": 0
      }}]
    }
  }
}
```

上例仅展示结构，deadline 的 0 是占位值，不能直接发送；实际可执行文件会自动替换成当前时间加 70000 的数字。任务响应结构为：

```json
{
  "jsonrpc": "2.0",
  "id": "rpc-001",
  "result": {
    "task": {
      "id": "服务端生成的任务UUID",
      "contextId": "你的会话UUID",
      "status": {"state": "TASK_STATE_COMPLETED"},
      "artifacts": [{
        "artifactId": "result",
        "parts": [{"data": {"facts": {}, "message": "订单和物流查询结果"}}]
      }]
    }
  }
}
```

| 字段 | 含义 |
|---|---|
| JSON-RPC `id` | 对应一对请求和响应；查询任务会使用新的 id |
| `messageId` | 标识一条输入消息，用于持久化去重；续办用新值 |
| `contextId` | 当前实现对应业务 conversationUuid，必须与服务 JWT 中的 conversation 一致 |
| `taskId` / `result.task.id` | 服务端任务标识；首次提交省略 taskId，续办传原值 |
| `parts[].data` | 本项目业务 v1 输入；A2A 负责承载，业务字段由项目定义 |
| `artifacts[].parts[].data` | 结构化业务结果、事实、政策引用、申请单信息 |
| `orderTaskId` / `policyTaskId` | 下游读取已授权的服务端证据任务，避免信任调用者拼接的订单事实 |
| `parentTaskId` / `traceId` | 关联委派链和项目回放轨迹 |

SSE 响应包含多段 `id: ...` 和 `data: {"jsonrpc":"2.0",...}`。每一帧是任务快照，可以观察 SUBMITTED → WORKING → COMPLETED，或者 INPUT_REQUIRED。HTTP 200 不代表业务成功，还要检查 JSON-RPC `error`、任务状态和业务结果。

## 5. 用断点和 SQL 看服务端过程

在 IDEA 添加以下断点，运行 02 或低层请求：

1. `A2aSupervisor.call`：观察 Card 发现、SDK Message/MessageSendParams、目标地址；这是完整链路中建立委派请求的位置。
2. `A2aController.rpc`：观察收到的原始 `body`，以及验签后的 scope；可直接核对 JSON 如何解码。
3. `A2aTaskService.send`：观察去重、任务创建及续办。
4. `A2aSpecialists.execute`：观察每个 Agent 的业务输入和 Artifact 结果。
5. `A2aTaskRepository.update`：观察状态快照及事件写入。

日志可搜索 `A2A completed`、`A2A failed`，再按 trace/task 关联。数据库只查看元数据即可：

```sql
SELECT id, agent_key, parent_task_id, trace_id, state, created_at, updated_at
FROM a2a_task
WHERE tenant_id = 1001 AND conversation_uuid = '替换为01返回的UUID'
ORDER BY created_at;

SELECT id, task_id, state, created_at
FROM a2a_event
WHERE tenant_id = 1001 AND task_id = '替换为任务UUID'
ORDER BY id;

SELECT task_id, message_id, request_hash, created_at
FROM a2a_message
WHERE tenant_id = 1001 AND task_id = '替换为任务UUID'
ORDER BY id;
```

snapshot 与 input_snapshot 是加密数据，直接 SQL 查询不会得到原始 JSON。需要研究完整数据时使用本地调试器观察对象，避免把完整服务 token、订单身份和明文快照写入日志。

排错：401 检查服务密钥是否与后端一致及目标 Agent；`A2A new tasks are disabled` 检查开关及是否重启；Task not found 检查任务所属 Agent、会话和租户；14 报任务不等待输入则先用 12 查看状态。更换一次全新演示会话可以重新运行 01。
