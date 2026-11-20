package com.omnimerchant.agent.a2a;

import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.spec.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** All queries include tenant + conversation + agent + caller; never accepts a tenant from message data. */
@Repository
public class A2aTaskRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final A2aIdentity identity;

    public A2aTaskRepository(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tx,
                             A2aIdentity identity) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(tx);
        this.identity = identity;
    }

    public <T> T transaction(java.util.function.Supplier<T> action) {
        return transaction.execute(status -> action.get());
    }

    public Task get(A2aIdentity.Scope scope, String id) {
        return get(scope, id, false);
    }

    public Task get(A2aIdentity.Scope scope, String id, boolean lock) {
        var rows = jdbc.queryForList(
            "SELECT snapshot FROM a2a_task WHERE id=? AND tenant_id=? AND conversation_uuid=? AND agent_key=? AND caller=?" + (
                lock ? " FOR UPDATE" : ""), id, scope.tenant(), scope.conversation(), scope.agent(), scope.caller());
        if (rows.isEmpty()) {
            throw new NoSuchElementException("Task not found");
        }
        return decode(rows.getFirst().get("snapshot").toString(), Task.class);
    }

    public Task replay(A2aIdentity.Scope scope, Message message) {
        var rows = jdbc.queryForList("SELECT task_id, request_hash FROM a2a_message WHERE tenant_id=? AND message_id=?",
            scope.tenant(), message.messageId());
        if (rows.isEmpty()) {
            return null;
        }
        if (!requestHash(message).equals(rows.getFirst().get("request_hash"))) {
            throw new IllegalArgumentException("Message ID already used with different content");
        }
        return get(scope, rows.getFirst().get("task_id").toString());
    }

    public Task create(A2aIdentity.Scope scope, Message message, A2aBusinessInput input) {
        var id = UUID.randomUUID().toString();
        var task =
            new Task(id, scope.conversation(), new TaskStatus(TaskState.TASK_STATE_SUBMITTED), List.of(), List.of(),
                Map.of("version", 1, "agent", scope.agent(), "mode", "A2A"));
        jdbc.update(
            "INSERT INTO a2a_task(id,tenant_id,conversation_uuid,agent_key,caller,parent_task_id,trace_id,state,snapshot,input_snapshot) VALUES(?,?,?,?,?,?,?,?,?,?)",
            id, scope.tenant(), scope.conversation(), scope.agent(), scope.caller(), input.parentTaskId(),
            input.traceId(), task.status().state().name(), encode(task), encode(input));
        message(scope, id, message);
        event(scope, task);
        return task;
    }

    public void message(A2aIdentity.Scope scope, String taskId, Message message) {
        jdbc.update("INSERT INTO a2a_message(tenant_id,task_id,message_id,request_hash,snapshot) VALUES(?,?,?,?,?)",
            scope.tenant(), taskId, message.messageId(), requestHash(message), encode(message));
    }

    public A2aBusinessInput input(A2aIdentity.Scope scope, String taskId) {
        get(scope, taskId);
        return decode(
            jdbc.queryForObject("SELECT input_snapshot FROM a2a_task WHERE tenant_id=? AND id=?", String.class,
                scope.tenant(), taskId), A2aBusinessInput.class);
    }

    public Task update(A2aIdentity.Scope scope, Task task, A2aBusinessInput input) {
        if (jdbc.update(
            "UPDATE a2a_task SET state=?, snapshot=?, input_snapshot=?, updated_at=CURRENT_TIMESTAMP(3) WHERE id=? AND tenant_id=? AND conversation_uuid=? AND agent_key=? AND caller=?",
            task.status().state().name(), encode(task), encode(input), task.id(), scope.tenant(), scope.conversation(),
            scope.agent(), scope.caller()) != 1) {
            throw new NoSuchElementException("Task not found");
        }
        event(scope, task);
        return task;
    }

    private void event(A2aIdentity.Scope scope, Task task) {
        jdbc.update("INSERT INTO a2a_event(tenant_id,task_id,state,snapshot) VALUES(?,?,?,?)", scope.tenant(),
            task.id(), task.status().state().name(), encode(task));
    }

    public List<Task> events(A2aIdentity.Scope scope, String id, long after) {
        get(scope, id);
        return jdbc.query(
            "SELECT snapshot FROM a2a_event WHERE tenant_id=? AND task_id=? ORDER BY id LIMIT 1000 OFFSET ?",
            (rs, n) -> decode(rs.getString(1), Task.class), scope.tenant(), id, after);
    }

    public List<Map<String, Object>> summaries(long tenant, String conversation) {
        var rows = jdbc.queryForList(
            "SELECT id, agent_key, parent_task_id, trace_id, state, created_at, updated_at, snapshot FROM a2a_task WHERE tenant_id=? AND conversation_uuid=? ORDER BY created_at LIMIT 200",
            tenant, conversation);
        for (var row : rows) {
            var task = decode(row.remove("snapshot").toString(), Task.class);
            var output = data(task);
            row.put("summary", output.getOrDefault("message", ""));
            row.put("mode", output.getOrDefault("mode", "A2A"));
            row.put("failureReason", A2aPrivacy.redact(String.valueOf(output.getOrDefault("reason", ""))));
            if (output.get("approval") instanceof Map<?, ?> approval) {
                row.put("approvalRequestNo", approval.get("requestNo"));
            }
        }
        return rows;
    }

    public Optional<String> waiting(A2aIdentity.Scope scope) {
        var ids = jdbc.queryForList(
            "SELECT id FROM a2a_task WHERE tenant_id=? AND conversation_uuid=? AND agent_key=? AND caller=? AND state=? ORDER BY updated_at DESC LIMIT 1",
            String.class, scope.tenant(), scope.conversation(), scope.agent(), scope.caller(),
            TaskState.TASK_STATE_INPUT_REQUIRED.name());
        return ids.stream().findFirst();
    }

    public void recover() {
        var rows = jdbc.queryForList(
            "SELECT id,tenant_id,conversation_uuid,agent_key,caller FROM a2a_task WHERE state IN (?,?)",
            TaskState.TASK_STATE_SUBMITTED.name(), TaskState.TASK_STATE_WORKING.name());
        for (var row : rows) {
            var scope = new A2aIdentity.Scope(((Number)row.get("tenant_id")).longValue(),
                row.get("conversation_uuid").toString(), row.get("agent_key").toString(), row.get("caller").toString());
            transaction(() -> {
                var task = get(scope, row.get("id").toString(), true);
                if (!task.status().state().isFinal() && task.status().state() != TaskState.TASK_STATE_INPUT_REQUIRED) {
                    update(scope, result(task, TaskState.TASK_STATE_FAILED,
                            Map.of("reason", "PROCESS_INTERRUPTED", "message", "处理已中断，请重试或联系人工客服。")),
                        input(scope, task.id()));
                }
                return null;
            });
        }
    }

    public static Task result(Task task, TaskState state, Map<String, Object> data) {
        return new Task(task.id(), task.contextId(), new TaskStatus(state), List.of(
            new Artifact("result", "business-result-v1", null, List.of(new DataPart(data)), Map.of("version", 1),
                null)), List.of(), task.metadata());
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> data(Task task) {
        if (task.artifacts() == null || task.artifacts().isEmpty()) {
            return Map.of();
        }
        return (Map<String, Object>)((DataPart)task.artifacts().getFirst().parts().getFirst()).data();
    }

    public static String json(Object value) {
        try {
            return JsonUtil.toJson(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid A2A JSON", e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        try {
            return JsonUtil.fromJson(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid A2A JSON", e);
        }
    }

    private String encode(Object value) {
        return identity.encrypt(json(value));
    }

    private <T> T decode(String value, Class<T> type) {
        return parse(identity.decrypt(value), type);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String requestHash(Message message) {
        var tree = JsonUtil.OBJECT_MAPPER.toJsonTree(message).getAsJsonObject();
        var parts = tree.getAsJsonArray("parts");
        if (parts != null) {
            for (var part : parts) {
                var object = part.getAsJsonObject();
                if (object.has("data") && object.get("data").isJsonObject()) {
                    var data = object.getAsJsonObject("data");
                    data.remove("deadlineEpochMs");
                    data.remove("traceId");
                    data.remove("parentTaskId");
                }
            }
        }
        return hash(tree.toString());
    }
}
