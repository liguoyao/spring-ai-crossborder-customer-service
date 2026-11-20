package com.omnimerchant.controller;

import com.google.gson.JsonObject;
import com.omnimerchant.agent.a2a.A2aConfiguration;
import com.omnimerchant.agent.a2a.A2aIdentity;
import com.omnimerchant.agent.a2a.A2aTaskRepository;
import com.omnimerchant.agent.a2a.A2aTaskService;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.CancelTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.GetTaskResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendMessageResponse;
import org.a2aproject.sdk.jsonrpc.common.wrappers.SendStreamingMessageResponse;
import org.a2aproject.sdk.spec.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutorService;

import static com.omnimerchant.agent.a2a.A2aTaskRepository.json;
import static com.omnimerchant.agent.a2a.A2aTaskRepository.parse;

@RestController
@RequestMapping("/api/a2a/{agent}")
public class A2aController {
    private final A2aConfiguration.Properties properties;
    private final A2aTaskService service;
    private final A2aTaskRepository repository;
    private final ExecutorService streams;

    public A2aController(A2aConfiguration.Properties properties, A2aTaskService service, A2aTaskRepository repository,
                         @Qualifier("a2aStreamExecutor") ExecutorService streams) {
        this.properties = properties;
        this.service = service;
        this.repository = repository;
        this.streams = streams;
    }

    @GetMapping(value = "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String card(@PathVariable String agent) {
        return json(AgentCard.builder().name(agent)
            .description("OmniMerchant " + agent + "; " + (properties.isFixture() ? "FIXTURE" : "LIVE"))
            .version("1.0.0").supportedInterfaces(
                List.of(new AgentInterface(TransportProtocol.JSONRPC.asString(), properties.endpoint(agent))))
            .capabilities(AgentCapabilities.builder().streaming(true).pushNotifications(false).build())
            .defaultInputModes(List.of("application/json")).defaultOutputModes(List.of("application/json"))
            .securitySchemes(Map.of("serviceBearer",
                new HTTPAuthSecurityScheme("JWT", "bearer", "Service token bound to tenant, conversation and agent")))
            .securityRequirements(List.of(new SecurityRequirement(Map.of("serviceBearer", List.of())))).skills(List.of(
                AgentSkill.builder().id(agent).name(agent)
                    .description("Tenant-authorized logistics after-sales specialist")
                    .tags(List.of("commerce", "human-approval")).build())).build());
    }

    @PostMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    public Object rpc(@PathVariable String agent, @RequestAttribute("a2a.scope") A2aIdentity.Scope scope,
                      @RequestBody String body) {
        Object requestId = null;
        try {
            if (body.length() > 32_768) {
                throw new IllegalArgumentException("Request too large");
            }
            var request = JsonUtil.OBJECT_MAPPER.fromJson(body, JsonObject.class);
            requestId = request.has("id") ? JsonUtil.OBJECT_MAPPER.fromJson(request.get("id"), Object.class) : null;
            if (!request.has("jsonrpc") || !"2.0".equals(
                request.get("jsonrpc").getAsString()) || requestId == null || !request.has("method")) {
                return error(requestId, -32600, "Invalid JSON-RPC request");
            }
            var method = request.get("method").getAsString();
            var params = request.getAsJsonObject("params");
            if (A2AMethods.SEND_MESSAGE_METHOD.equals(method) || A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(
                method)) {
                if (!properties.isEnabled()) {
                    return error(requestId, -32000, "A2A new tasks are disabled");
                }
                var input = parse(params.toString(), MessageSendParams.class);
                var task = service.send(scope, input.message());
                return A2AMethods.SEND_STREAMING_MESSAGE_METHOD.equals(method) ? stream(scope, task.id(), requestId)
                    : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                        .body(json(new SendMessageResponse(requestId, task)));
            }
            if (A2AMethods.GET_TASK_METHOD.equals(method)) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                    .body(json(new GetTaskResponse(requestId, repository.get(scope, params.get("id").getAsString()))));
            }
            if (A2AMethods.CANCEL_TASK_METHOD.equals(method)) {
                return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(
                    json(new CancelTaskResponse(requestId, service.cancel(scope, params.get("id").getAsString()))));
            }
            if (A2AMethods.SUBSCRIBE_TO_TASK_METHOD.equals(method)) {
                return stream(scope, params.get("id").getAsString(), requestId);
            }
            return error(requestId, -32601, "Method not supported");
        } catch (A2AError protocolError) {
            return error(requestId, protocolError.getCode(), protocolError.getMessage());
        } catch (com.google.gson.JsonParseException invalidJson) {
            return error(requestId, A2AErrorCodes.JSON_PARSE.code(), "Invalid JSON");
        } catch (NoSuchElementException hidden) {
            return error(requestId, -32001, "Task not found");
        } catch (SecurityException denied) {
            return error(requestId, -32001, "Task not found");
        } catch (IllegalArgumentException invalid) {
            return error(requestId, -32602, invalid.getMessage());
        } catch (Exception failed) {
            return error(requestId, -32603, "A2A request failed");
        }
    }

    private SseEmitter stream(A2aIdentity.Scope scope, String id, Object requestId) {
        repository.get(scope, id);
        var emitter = new SseEmitter(75_000L);
        var closed = new java.util.concurrent.atomic.AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
        emitter.onError(error -> closed.set(true));
        try {
            streams.submit(() -> {
                long offset = 0;
                var deadline = System.currentTimeMillis() + 70_000;
                try {
                    while (!closed.get() && System.currentTimeMillis() < deadline) {
                        var events = repository.events(scope, id, offset);
                        for (var task : events) {
                            emitter.send(SseEmitter.event().id(Long.toString(++offset))
                                .data(json(new SendStreamingMessageResponse(requestId, task))));
                            if (task.status().state().isFinal() || task.status().state().isInterrupted()) {
                                emitter.complete();
                                return;
                            }
                        }
                        Thread.sleep(100);
                    }
                    emitter.complete();
                } catch (Exception error) {
                    emitter.completeWithError(error);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException full) {
            emitter.completeWithError(full);
        }
        return emitter;
    }

    private ResponseEntity<String> error(Object id, int code, String message) {
        var response = new LinkedHashMap<String, Object>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message == null ? "Invalid request" : message));
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json(response));
    }
}
