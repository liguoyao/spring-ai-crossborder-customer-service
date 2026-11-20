package com.omnimerchant.agent.a2a;

import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.service.AgentTraceService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.a2aproject.sdk.spec.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static com.omnimerchant.agent.a2a.A2aTaskRepository.*;
import static org.a2aproject.sdk.spec.TaskState.*;

@Service
public class A2aTaskService {
    private final A2aTaskRepository repository;
    private final A2aSpecialists specialists;
    private final ExecutorService executor;
    private final AgentTraceService traces;
    private final A2aConfiguration.Properties properties;
    private final A2aMetrics metrics;

    public A2aTaskService(A2aTaskRepository repository, A2aSpecialists specialists,
                          @Qualifier("a2aAgentExecutor") ExecutorService executor, AgentTraceService traces,
                          A2aConfiguration.Properties properties, A2aMetrics metrics) {
        this.repository = repository;
        this.specialists = specialists;
        this.executor = executor;
        this.traces = traces;
        this.properties = properties;
        this.metrics = metrics;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        if (properties.isEnabled()) {
            if (properties.getSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalStateException(
                    "A2A_SERVICE_SECRET must contain at least 32 UTF-8 bytes when A2A is enabled");
            }
            repository.recover();
        }
    }

    public Task send(A2aIdentity.Scope scope, Message message) {
        if (message == null || message.messageId() == null || message.messageId().isBlank() || message.messageId()
            .length() > 128 || !scope.conversation().equals(
            message.contextId()) || message.role() != Message.Role.ROLE_USER || message.parts() == null || message.parts()
            .size() != 1 || !(message.parts().getFirst() instanceof DataPart part)) {
            throw new IllegalArgumentException("Expected business-v1 data message in authorized conversation");
        }
        var input = parse(json(part.data()), A2aBusinessInput.class);
        if (input.deadlineEpochMs() > System.currentTimeMillis() + 75_000) {
            throw new IllegalArgumentException("Deadline exceeds turn budget");
        }
        Task submitted;
        try {
            submitted = repository.transaction(() -> {
                var replay = repository.replay(scope, message);
                if (replay != null) {
                    metrics.count(scope.agent(), "duplicate");
                    return replay;
                }
                if (message.taskId() == null || message.taskId().isBlank()) {
                    return repository.create(scope, message, input);
                }
                var task = repository.get(scope, message.taskId(), true);
                if (task.status().state() != TASK_STATE_INPUT_REQUIRED) {
                    throw new IllegalArgumentException("Task is not waiting for input");
                }
                repository.message(scope, task.id(), message);
                return repository.update(scope,
                    new Task(task.id(), task.contextId(), new TaskStatus(TASK_STATE_SUBMITTED), task.artifacts(),
                        List.of(), task.metadata()), input);
            });
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            submitted = repository.replay(scope, message);
            if (submitted == null) {
                throw duplicate;
            }
        }
        if (submitted.status().state() == TASK_STATE_SUBMITTED) {
            var taskId = submitted.id();
            try {
                executor.submit(() -> work(scope, taskId));
            } catch (RejectedExecutionException full) {
                fail(scope, taskId, "CAPACITY_EXCEEDED");
            }
        }
        return repository.get(scope, submitted.id());
    }

    private void work(A2aIdentity.Scope scope, String id) {
        var start = System.currentTimeMillis();
        TenantContextHolder.set(scope.tenant());
        try {
            var claimed = repository.transaction(() -> {
                var task = repository.get(scope, id, true);
                if (task.status().state() != TASK_STATE_SUBMITTED) {
                    return false;
                }
                repository.update(scope,
                    new Task(task.id(), task.contextId(), new TaskStatus(TASK_STATE_WORKING), task.artifacts(),
                        List.of(), task.metadata()), repository.input(scope, id));
                return true;
            });
            if (!claimed) {
                return;
            }
            var input = repository.input(scope, id);
            CallContextHolder.set("RETURN_REFUND", scope.conversation(), input.traceId());
            var completed = repository.transaction(() -> {
                var task = repository.get(scope, id, true);
                if (task.status().state() != TASK_STATE_WORKING) {
                    return task;
                }
                return repository.update(scope, specialists.execute(scope, task, input), input);
            });
            metrics.count(scope.agent(), completed.status().state().name());
            metrics.duration(scope.agent(), System.currentTimeMillis() - start);
            org.slf4j.LoggerFactory.getLogger(getClass())
                .info("A2A completed trace={} task={} agent={} state={}", input.traceId(), id, scope.agent(),
                    completed.status().state());
            traces.addStep(input.traceId(), "A2A", scope.agent(), completed.status().state().name(), "task=" + id,
                String.valueOf(data(completed).getOrDefault("message", "")), id,
                (int)(System.currentTimeMillis() - start), Map.of("taskId", id, "agent", scope.agent(), "parentTaskId",
                    input.parentTaskId() == null ? "" : input.parentTaskId(), "mode",
                    properties.isFixture() ? "FIXTURE" : "LIVE"));
        } catch (Exception error) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("A2A failed task={} agent={} category={}", id, scope.agent(), error.getClass().getSimpleName());
            metrics.count(scope.agent(), "failure");
            fail(scope, id, "SPECIALIST_FAILED");
        } finally {
            CallContextHolder.clear();
            TenantContextHolder.clear();
        }
    }

    private void fail(A2aIdentity.Scope scope, String id, String reason) {
        repository.transaction(() -> {
            var task = repository.get(scope, id, true);
            if (!task.status().state().isFinal()) {
                repository.update(scope, result(task, TASK_STATE_FAILED,
                        Map.of("reason", reason, "message", "智能体暂时无法完成处理，请联系人工客服。")),
                    repository.input(scope, id));
            }
            return null;
        });
    }

    public Task cancel(A2aIdentity.Scope scope, String id) {
        return repository.transaction(() -> {
            var task = repository.get(scope, id, true);
            if (task.status().state().isFinal()) {
                throw new TaskNotCancelableError("Task is terminal; existing approvals are preserved");
            }
            return repository.update(scope,
                result(task, TASK_STATE_CANCELED, Map.of("message", "处理已取消；已提交的审批申请不会撤销。")),
                repository.input(scope, id));
        });
    }
}
