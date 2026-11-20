package com.omnimerchant.agent.a2a;

import com.omnimerchant.agent.dto.ChatStreamEvent;
import com.omnimerchant.agent.service.AgentStateMachineService;
import com.omnimerchant.agent.service.AgentTraceService;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import static com.omnimerchant.agent.a2a.A2aTaskRepository.*;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_COMPLETED;
import static org.a2aproject.sdk.spec.TaskState.TASK_STATE_INPUT_REQUIRED;

@Service
public class A2aSupervisor {
    private final A2aConfiguration.Properties properties;
    private final A2aIdentity identity;
    private final A2aTaskRepository repository;
    private final AgentStateMachineService states;
    private final AgentTraceService traces;
    private final com.omnimerchant.agent.tool.EscalationTools escalation;

    public A2aSupervisor(A2aConfiguration.Properties properties, A2aIdentity identity, A2aTaskRepository repository,
                         AgentStateMachineService states, AgentTraceService traces,
                         com.omnimerchant.agent.tool.EscalationTools escalation) {
        this.properties = properties;
        this.identity = identity;
        this.repository = repository;
        this.states = states;
        this.traces = traces;
        this.escalation = escalation;
    }

    public boolean accepts(long tenant, String conversation, String message) {
        if (!properties.isEnabled()) {
            return false;
        }
        if (waiting(tenant, conversation, "after-sales").isPresent() || waiting(tenant, conversation,
            "order-logistics").isPresent()) {
            return true;
        }
        var text = message.toLowerCase(Locale.ROOT);
        return matches(text, "物流|包裹|快递|没收到|未收到|shipment|package|delivery|not received") && matches(text,
            "退款|补发|退钱|refund|replacement|replace");
    }

    public Flux<ChatStreamEvent> chat(long tenant, String conversation, String message, String trace) {
        return Flux.<ChatStreamEvent>create(sink -> {
            var started = System.currentTimeMillis();
            TenantContextHolder.set(tenant);
            com.omnimerchant.agent.context.CallContextHolder.set("RETURN_REFUND", conversation, trace);
            try {
                if (matches(message.toLowerCase(Locale.ROOT), "投诉|生气|糟糕|angry|furious|人工|human")) {
                    sink.next(
                        ChatStreamEvent.finalAnswer("这项物流售后争议需要人工客服协助，请通过人工客服入口继续处理。"));
                    handoff(tenant, conversation, trace, "HUMAN_REVIEW_REQUIRED");
                    traces.completeRun(trace, "HUMAN_REVIEW_REQUIRED", 0, (int)(System.currentTimeMillis() - started));
                    sink.complete();
                    return;
                }
                var deadline = started + 70_000;
                var pendingSales = waiting(tenant, conversation, "after-sales");
                var pendingOrder = waiting(tenant, conversation, "order-logistics");
                A2aBusinessInput previous = null;
                if (pendingSales.isPresent()) {
                    previous = repository.input(scope(tenant, conversation, "after-sales"), pendingSales.get());
                } else if (pendingOrder.isPresent()) {
                    previous = repository.input(scope(tenant, conversation, "order-logistics"), pendingOrder.get());
                }
                var input = extract(message, previous, trace, deadline);
                Task order;
                Task policy;
                if (pendingSales.isPresent()) {
                    order = repository.get(scope(tenant, conversation, "order-logistics"), input.orderTaskId());
                    policy = repository.get(scope(tenant, conversation, "policy"), input.policyTaskId());
                } else {
                    sink.next(ChatStreamEvent.status("正在核验订单和物流"));
                    order = call(tenant, conversation, "order-logistics", input, pendingOrder.orElse(null), deadline,
                        sink::isCancelled);
                    if (order.status().state() != TASK_STATE_COMPLETED) {
                        finish(tenant, conversation, trace, order, started, sink);
                        return;
                    }
                    sink.next(ChatStreamEvent.status("正在核查售后政策"));
                    input = new A2aBusinessInput(1, input.orderId(), input.customerIdentity(), input.action(),
                        input.question(), order.id(), null, trace, order.id(), deadline);
                    policy = call(tenant, conversation, "policy", input, null, deadline, sink::isCancelled);
                    if (policy.status().state() != TASK_STATE_COMPLETED || !Boolean.TRUE.equals(
                        data(policy).get("eligible"))) {
                        finish(tenant, conversation, trace, policy, started, sink);
                        return;
                    }
                }
                sink.next(ChatStreamEvent.status("正在处理内部售后审批申请"));
                input =
                    new A2aBusinessInput(1, input.orderId(), input.customerIdentity(), input.action(), input.question(),
                        order.id(), policy.id(), trace, policy.id(), deadline);
                var sales = call(tenant, conversation, "after-sales", input, pendingSales.orElse(null), deadline,
                    sink::isCancelled);
                finish(tenant, conversation, trace, sales, started, sink);
            } catch (Exception error) {
                states.failRun(tenant, conversation, trace, "A2A_EXECUTION_FAILED");
                handoff(tenant, conversation, trace, "A2A_EXECUTION_FAILED");
                traces.failRun(trace, error, (int)(System.currentTimeMillis() - started));
                sink.next(ChatStreamEvent.error("售后协同暂时无法完成，请重试或联系人工客服。已提交的申请不会重复执行。"));
                sink.complete();
            } finally {
                TenantContextHolder.clear();
                com.omnimerchant.agent.context.CallContextHolder.clear();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void finish(long tenant, String conversation, String trace, Task task, long started,
                        reactor.core.publisher.FluxSink<ChatStreamEvent> sink) {
        var text = String.valueOf(data(task).getOrDefault("message", "请联系人工客服继续处理。"));
        var input = repository.input(
            scope(tenant, conversation, String.valueOf(task.metadata().getOrDefault("agent", "after-sales"))),
            task.id());
        if (input.orderTaskId() != null) {
            var orderData = data(repository.get(scope(tenant, conversation, "order-logistics"), input.orderTaskId()));
            if (orderData.get("facts") instanceof Map<?, ?> facts && facts.get(
                "logistics") instanceof Map<?, ?> logistics) {
                text = "物流核查：" + String.valueOf(logistics.get("status")) + "。" + text;
            }
        }
        if (input.policyTaskId() != null) {
            var policyData = data(repository.get(scope(tenant, conversation, "policy"), input.policyTaskId()));
            if (policyData.get("policy") instanceof Map<?, ?> policy && policy.get(
                "citations") instanceof List<?> citations) {
                var sources = citations.stream().filter(Map.class::isInstance).map(Map.class::cast)
                    .map(citation -> String.valueOf(citation.getOrDefault("sourceTitle", citation.get("chunkUuid"))))
                    .distinct().limit(3).toList();
                if (!sources.isEmpty()) {
                    text += " 政策依据：" + String.join("、", sources) + "。";
                }
            }
        }
        if (data(task).get("approval") instanceof Map<?, ?> approval && approval.get("requestNo") != null) {
            text += " 审批单号：" + approval.get("requestNo") + "。";
        }
        if (properties.isFixture()) {
            text = "[FIXTURE 演示] " + text;
        }
        if (task.status().state() == TASK_STATE_INPUT_REQUIRED) {
            states.requireCustomerInput(tenant, conversation, trace,
                "order-logistics".equals(task.metadata().get("agent")));
        } else if (task.status().state() != TASK_STATE_COMPLETED || Boolean.FALSE.equals(data(task).get("eligible"))) {
            handoff(tenant, conversation, trace, "A2A_EVIDENCE_OR_EXECUTION_FAILURE");
        }
        states.completeRun(tenant, conversation, trace);
        traces.completeRun(trace, text, 0, (int)(System.currentTimeMillis() - started));
        sink.next(ChatStreamEvent.finalAnswer(text));
        sink.complete();
    }

    private void handoff(long tenant, String conversation, String trace, String reason) {
        try {
            var result = escalation.escalateToHuman(reason, "物流异常售后协同需要人工核查；trace=" + trace, 3);
            states.toolSucceeded(tenant, conversation, trace, "escalateToHuman", json(result));
        } catch (Exception unavailable) {
            traces.addStep(trace, "A2A", "handoff", "FAILED", reason, "Human handoff unavailable", null, 0, Map.of());
        }
    }

    private Task call(long tenant, String conversation, String agent, A2aBusinessInput input, String taskId,
                      long deadline, java.util.function.BooleanSupplier cancelled) throws Exception {
        var endpoint = properties.endpoint(agent);
        var context = new ClientCallContext(Map.of(),
            Map.of("Authorization", "Bearer " + identity.issue(tenant, conversation, agent)));
        var http = new A2aDeadlineHttpClient(deadline);
        var discovery =
            http.createGet().url(endpoint + "/.well-known/agent-card.json").addHeaders(context.getHeaders()).get();
        if (!discovery.success()) {
            throw new SecurityException("Agent discovery failed");
        }
        var card = parse(discovery.body(), AgentCard.class);
        if (card.supportedInterfaces().stream().noneMatch(it -> endpoint.equals(it.url()) && "1.0".equals(
            it.protocolVersion()) && TransportProtocol.JSONRPC.asString().equals(it.protocolBinding()))) {
            throw new SecurityException("Agent card does not match configured A2A v1 endpoint");
        }
        var transport =
            new JSONRPCTransport(http, card, new AgentInterface(TransportProtocol.JSONRPC.asString(), endpoint),
                List.of());
        var businessId = hash(
            agent + ":" + conversation + ":" + input.orderId() + ":" + input.customerIdentity() + ":" + input.action() + ":" + input.question() + ":" + input.orderTaskId() + ":" + input.policyTaskId());
        var message =
            Message.builder().role(Message.Role.ROLE_USER).messageId(businessId).contextId(conversation).taskId(taskId)
                .parts(List.of(new DataPart(parse(json(input), Map.class)))).build();
        try {
            Task task;
            try {
                task = (Task)transport.sendMessage(new MessageSendParams(message, null, Map.of()), context);
            } catch (Exception timeout) {
                // Resolve an uncertain send from durable message identity before considering any retry.
                task = repository.replay(scope(tenant, conversation, agent), message);
                if (task == null) {
                    throw timeout;
                }
            }
            while (!task.status().state().isFinal() && !task.status().state().isInterrupted()) {
                if (cancelled.getAsBoolean() || System.currentTimeMillis() >= deadline) {
                    try {
                        transport.cancelTask(new CancelTaskParams(task.id()), context);
                    } catch (Exception ignored) {
                    }
                    throw new java.util.concurrent.TimeoutException("A2A turn deadline or cancellation");
                }
                Thread.sleep(100);
                task = transport.getTask(new TaskQueryParams(task.id()), context);
            }
            return task;
        } finally {
            transport.close();
        }
    }

    private Optional<String> waiting(long tenant, String conversation, String agent) {
        return repository.waiting(scope(tenant, conversation, agent));
    }

    private A2aIdentity.Scope scope(long tenant, String conversation, String agent) {
        return new A2aIdentity.Scope(tenant, conversation, agent, "supervisor");
    }

    static A2aBusinessInput extract(String message, A2aBusinessInput previous, String trace, long deadline) {
        var order = Pattern.compile("(?i)(?:ORD-[A-Z0-9_-]+|#[A-Z0-9_-]{2,64})").matcher(message);
        var email = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(message);
        var phone = Pattern.compile("(?<!\\d)\\+?\\d[\\d -]{7,18}\\d(?!\\d)").matcher(message);
        String orderId = order.find() ? order.group() : previous == null ? null : previous.orderId();
        String customer = email.find() ? email.group() : phone.find() ? phone.group().replace(" ", "").replace("-", "")
            : previous == null ? null : previous.customerIdentity();
        var lower = message.toLowerCase(Locale.ROOT);
        boolean refund = matches(lower, "退款|退钱|refund"), replace = matches(lower, "补发|replacement|replace");
        boolean conditional = matches(lower, "退款还是|退款或|refund or|补发还是");
        boolean negated =
            matches(lower, "不要|不想|不申请|不退款|don't|do not|not refund|only check|只查|仅查|取消申请");
        boolean explicitRequest = matches(lower,
            "(?:申请|我要|我想要|想要|帮我|请|要求|给我)(?:退款|补发)|(?:request|want|please|apply|need).{0,20}(?:refund|replacement|replace)") || previous != null && lower.trim()
            .matches("(?:退款|补发|refund|replacement)[。.! ]*");
        String action = negated || conditional ? null
            : refund != replace && explicitRequest ? refund ? "refund" : "replacement"
                : previous == null ? null : previous.action();
        return new A2aBusinessInput(1, orderId, customer, action,
            previous == null ? A2aPrivacy.redact(message) : previous.question(),
            previous == null ? null : previous.orderTaskId(), previous == null ? null : previous.policyTaskId(), trace,
            previous == null ? null : previous.parentTaskId(), deadline);
    }

    private static boolean matches(String text, String regex) {
        return Pattern.compile(regex).matcher(text).find();
    }
}
