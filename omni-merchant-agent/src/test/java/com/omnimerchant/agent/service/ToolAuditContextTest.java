package com.omnimerchant.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.omnimerchant.agent.context.CallContextHolder;
import com.omnimerchant.agent.entity.ToolCallLog;
import com.omnimerchant.agent.mapper.AgentIdempotencyGuardMapper;
import com.omnimerchant.agent.mapper.ToolCallLogMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ToolAuditContextTest {
    @AfterEach void clear() {
        TenantContextHolder.clear();
        CallContextHolder.clear();
        MDC.clear();
    }

    private ToolCallback guarded(ToolCallback callback) {
        var service = new AgentExecutionGuardService(mock(StringRedisTemplate.class),
                mock(AgentIdempotencyGuardMapper.class), mock(AgentStateMachineService.class));
        return service.guardedCallbacks(new ToolCallback[]{callback},
                new AgentOrchestratorService.SpecialistPlan("product", "product",
                        List.of("searchProductCatalog"), "LOW", false, false, false)).getFirst();
    }

    private ToolCallback callback() {
        var callback = mock(ToolCallback.class);
        var definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("searchProductCatalog");
        when(callback.getToolDefinition()).thenReturn(definition);
        return callback;
    }

    private ToolContext context() {
        return new ToolContext(Map.of("tenantId", 1L, "conversationUuid", "conversation-1",
                "traceId", "main-trace", "intent", "PRODUCT_ADVICE",
                "allowedTools", List.of("searchProductCatalog")));
    }

    @Test
    void toolAuditUsesMainTraceAndConversationOnCallbackThreadAndCleansContext() throws Exception {
        var mapper = mock(ToolCallLogMapper.class);
        var audit = new ToolAuditService(mapper, new ObjectMapper(), mock(AgentTraceService.class));
        var callback = callback();
        when(callback.call(any(String.class), any(ToolContext.class))).thenAnswer(call ->
                audit.record("searchProductCatalog", Map.of(), () -> "no products"));
        var guarded = guarded(callback);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> {
                TenantContextHolder.set(1L);
                try {
                    assertThat(CallContextHolder.get()).isNull();
                    assertThat(guarded.call("{}", context())).isEqualTo("no products");
                    assertThat(CallContextHolder.get()).isNull();
                    assertThat(MDC.get("traceId")).isNull();
                } finally { TenantContextHolder.clear(); }
            }).get(10, TimeUnit.SECONDS);
        }
        var log = ArgumentCaptor.forClass(ToolCallLog.class);
        verify(mapper).insert(log.capture());
        assertThat(log.getValue().getTraceId()).isEqualTo("main-trace");
        assertThat(log.getValue().getConversationUuid()).isEqualTo("conversation-1");
        assertThat(log.getValue().getTenantId()).isEqualTo(1L);
    }

    @Test
    void restoresPreviousContextWhenToolFails() {
        TenantContextHolder.set(1L);
        CallContextHolder.set("OLD", "old-conversation", "old-trace");
        MDC.put("traceId", "old-mdc-trace");
        var callback = callback();
        when(callback.call(any(String.class), any(ToolContext.class))).thenAnswer(call -> {
            assertThat(CallContextHolder.get().traceId()).isEqualTo("main-trace");
            assertThat(MDC.get("traceId")).isEqualTo("main-trace");
            throw new IllegalStateException("tool failed");
        });
        assertThatThrownBy(() -> guarded(callback).call("{}", context())).hasMessage("tool failed");
        assertThat(CallContextHolder.get().conversationUuid()).isEqualTo("old-conversation");
        assertThat(CallContextHolder.get().traceId()).isEqualTo("old-trace");
        assertThat(MDC.get("traceId")).isEqualTo("old-mdc-trace");
    }
}
