package com.omnimerchant;

import com.omnimerchant.agent.a2a.*;
import com.omnimerchant.agent.service.AgentTraceService;
import com.omnimerchant.agent.service.AgentStateMachineService;
import com.omnimerchant.agent.tool.EscalationTools;
import com.omnimerchant.config.A2aSecurityConfig;
import com.omnimerchant.config.WebMvcConfig;
import com.omnimerchant.controller.A2aController;
import com.omnimerchant.tenant.interceptor.TenantInterceptor;
import org.a2aproject.sdk.client.transport.jsonrpc.JSONRPCTransport;
import org.a2aproject.sdk.client.transport.spi.interceptors.ClientCallContext;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import javax.sql.DataSource;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.omnimerchant.agent.a2a.A2aTaskRepository.*;
import static org.a2aproject.sdk.spec.TaskState.*;

@SpringBootTest(classes = A2aHttpIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.location=optional:classpath:/a2a-test.properties",
                "spring.datasource.url=jdbc:h2:mem:a2a;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa", "spring.datasource.password=", "spring.flyway.enabled=false",
                "spring.ai.openai.api-key=fixture-not-used", "spring.ai.model.chat=none", "spring.ai.model.embedding=none",
                "spring.ai.model.audio.speech=none", "spring.ai.model.audio.transcription=none", "spring.ai.model.image=none", "spring.ai.model.moderation=none",
                "agent.a2a.enabled=true", "agent.a2a.secret=separate-a2a-integration-test-secret-1234567890"})
class A2aHttpIntegrationTest {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({A2aConfiguration.class, A2aIdentity.class, A2aTaskRepository.class, A2aTaskService.class,
            A2aMetrics.class, A2aController.class, A2aSecurityConfig.class, WebMvcConfig.class, TenantInterceptor.class, A2aSupervisor.class})
    static class App {
        @Bean InitializingBean schema(DataSource source) {
            return () -> new ResourceDatabasePopulator(new ClassPathResource("db/migration/mysql/V25__a2a_collaboration.sql")).execute(source);
        }
    }
    @LocalServerPort int port;
    @Autowired A2aIdentity identity;
    @Autowired A2aTaskRepository repository;
    @Autowired A2aTaskService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired A2aConfiguration.Properties properties;
    @MockitoBean A2aSpecialists specialists;
    @MockitoBean AgentTraceService traces;
    @MockitoBean AgentStateMachineService states;
    @MockitoBean EscalationTools escalation;
    @Autowired A2aSupervisor supervisor;

    @BeforeEach void setup() {
        properties.setBaseUrl("http://127.0.0.1:" + port);
        properties.setEnabled(true);
        properties.setFixture(false);
        jdbc.update("DELETE FROM a2a_event"); jdbc.update("DELETE FROM a2a_message"); jdbc.update("DELETE FROM a2a_task");
        when(specialists.execute(any(), any(), any())).thenAnswer(call -> {
            Task task = call.getArgument(1); A2aBusinessInput input = call.getArgument(2);
            return result(task, input.customerIdentity() == null ? TASK_STATE_INPUT_REQUIRED : TASK_STATE_COMPLETED,
                    Map.of("message", "FIXTURE test result", "mode", "FIXTURE"));
        });
    }
    private A2aIdentity.Scope scope(String agent) { return new A2aIdentity.Scope(1, "conversation-1", agent, "supervisor"); }
    private JSONRPCTransport transport(String agent) { return new JSONRPCTransport(properties.endpoint(agent)); }
    private ClientCallContext context(long tenant, String conversation, String agent) {
        return new ClientCallContext(Map.of(), Map.of("Authorization", "Bearer " + identity.issue(tenant, conversation, agent)));
    }
    private Message message(String id, String taskId, String customer) {
        var input = new A2aBusinessInput(1, "ORD-001", customer, "refund", "包裹未收到，申请退款", null, null, "trace-1", null, System.currentTimeMillis() + 60_000);
        return Message.builder().role(Message.Role.ROLE_USER).messageId(id).contextId("conversation-1").taskId(taskId)
                .parts(List.of(new DataPart(parse(json(input), Map.class)))).build();
    }
    private Task await(JSONRPCTransport transport, Task task, ClientCallContext context) throws Exception {
        long end = System.currentTimeMillis() + 5000;
        while (!task.status().state().isFinal() && !task.status().state().isInterrupted() && System.currentTimeMillis() < end) {
            Thread.sleep(20); task = transport.getTask(new TaskQueryParams(task.id()), context);
        }
        return task;
    }
    @Test void allThreeAgentsExposeCardsAndRoundTripViaOfficialSdk() throws Exception {
        for (var agent : List.of("order-logistics", "policy", "after-sales")) {
            var token = identity.issue(1, "conversation-1", agent);
            var card = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(properties.endpoint(agent) + "/.well-known/agent-card.json"))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, card.statusCode());
            assertTrue(card.body().contains("1.0"));
            var transport = transport(agent);
            try {
                var context = context(1, "conversation-1", agent);
                var task = (Task) transport.sendMessage(new MessageSendParams(message(agent, null, "buyer@example.com"), null, Map.of()), context);
                assertEquals(TASK_STATE_COMPLETED, await(transport, task, context).status().state());
            } finally { transport.close(); }
        }
    }
    @Test void waitingTaskResumesAndRepeatedMessageDoesNotExecuteAgain() throws Exception {
        var transport = transport("order-logistics"); var context = context(1, "conversation-1", "order-logistics");
        try {
            var original = message("initial", null, null);
            var task = await(transport, (Task) transport.sendMessage(new MessageSendParams(original, null, Map.of()), context), context);
            assertEquals(TASK_STATE_INPUT_REQUIRED, task.status().state());
            var completed = await(transport, (Task) transport.sendMessage(new MessageSendParams(message("resume", task.id(), "buyer@example.com"), null, Map.of()), context), context);
            assertEquals(task.id(), completed.id()); assertEquals(TASK_STATE_COMPLETED, completed.status().state());
            transport.sendMessage(new MessageSendParams(original, null, Map.of()), context);
            verify(specialists, times(2)).execute(any(), any(), any());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM a2a_message", Integer.class));
            var encrypted = jdbc.queryForObject("SELECT input_snapshot FROM a2a_task WHERE id=?", String.class, task.id());
            assertFalse(encrypted.contains("buyer@example.com"));
        } finally { transport.close(); }
    }
    @Test void realSsePublishesTaskLifecycle() throws Exception {
        var transport = transport("policy"); var events = new CopyOnWriteArrayList<StreamingEventKind>();
        var errors = new CopyOnWriteArrayList<Throwable>(); var done = new CountDownLatch(1);
        try {
            transport.sendMessageStreaming(new MessageSendParams(message("stream", null, "buyer@example.com"), null, Map.of()), event -> {
                events.add(event); if (event instanceof Task task && task.status().state().isFinal()) done.countDown();
            }, error -> { if (error != null) errors.add(error); done.countDown(); }, context(1, "conversation-1", "policy"));
            assertTrue(done.await(10, TimeUnit.SECONDS)); assertTrue(errors.isEmpty(), errors.toString());
            assertTrue(events.stream().anyMatch(event -> event instanceof Task task && task.status().state() == TASK_STATE_COMPLETED));
        } finally { transport.close(); }
    }
    @Test void rejectsMissingWidgetWrongAgentAndForgedTaskScopes() throws Exception {
        var url = URI.create(properties.endpoint("policy") + "/.well-known/agent-card.json");
        for (var header : List.of("", "Bearer invalid-widget-token", "Bearer " + identity.issue(1, "conversation-1", "after-sales"))) {
            var request = HttpRequest.newBuilder(url).GET();
            if (!header.isBlank()) request.header("Authorization", header);
            assertEquals(401, HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        var transport = transport("order-logistics");
        try {
            var task = await(transport, (Task)transport.sendMessage(new MessageSendParams(message("scope", null, "buyer@example.com"), null, Map.of()), context(1, "conversation-1", "order-logistics")), context(1, "conversation-1", "order-logistics"));
            assertThrows(Exception.class, () -> transport.getTask(new TaskQueryParams(task.id()), context(2, "conversation-1", "order-logistics")));
            assertThrows(Exception.class, () -> transport.getTask(new TaskQueryParams(task.id()), context(1, "another-conversation", "order-logistics")));
            assertThrows(Exception.class, () -> transport.sendMessage(new MessageSendParams(message("scope", null, "different@example.com"), null, Map.of()), context(1, "conversation-1", "order-logistics")));
        } finally { transport.close(); }
    }
    @Test void cancelWaitingTaskAndRecoverInterruptedWorkPreserveWaitingTasks() throws Exception {
        var transport = transport("order-logistics"); var context = context(1, "conversation-1", "order-logistics");
        try {
            var task = await(transport, (Task)transport.sendMessage(new MessageSendParams(message("cancel", null, null), null, Map.of()), context), context);
            assertEquals(TASK_STATE_CANCELED, transport.cancelTask(new CancelTaskParams(task.id()), context).status().state());
            var notCancelable = assertThrows(A2AClientException.class, () -> transport.cancelTask(new CancelTaskParams(task.id()), context));
            assertInstanceOf(TaskNotCancelableError.class, notCancelable.getCause());
            var waiting = await(transport, (Task)transport.sendMessage(new MessageSendParams(message("waiting", null, null), null, Map.of()), context), context);
            var working = repository.transaction(() -> repository.create(scope("policy"), message("interrupted", null, null),
                    new A2aBusinessInput(1, "ORD-001", null, null, "question", null, null, null, null, System.currentTimeMillis()+1000)));
            repository.recover();
            assertEquals(TASK_STATE_FAILED, repository.get(scope("policy"), working.id()).status().state());
            assertEquals(TASK_STATE_INPUT_REQUIRED, repository.get(scope("order-logistics"), waiting.id()).status().state());
        } finally { transport.close(); }
    }
    @Test void disabledModeAllowsExistingQueriesButRejectsNewSubmissions() throws Exception {
        var transport = transport("policy"); var context = context(1, "conversation-1", "policy");
        try {
            var task = await(transport, (Task)transport.sendMessage(new MessageSendParams(message("disable", null, "buyer@example.com"), null, Map.of()), context), context);
            properties.setEnabled(false);
            assertEquals(task.id(), transport.getTask(new TaskQueryParams(task.id()), context).id());
            assertThrows(Exception.class, () -> transport.sendMessage(new MessageSendParams(message("disabled-new", null, null), null, Map.of()), context));
        } finally { transport.close(); }
    }
    @Test void supervisorDiscoversAndCallsThreeSpecialistsOverHttp() {
        properties.setFixture(true);
        var orders = mock(com.omnimerchant.agent.tool.OrderTools.class);
        var logistics = mock(com.omnimerchant.agent.tool.LogisticsTools.class);
        var policies = mock(com.omnimerchant.knowledge.tool.PolicyTools.class);
        var guard = mock(com.omnimerchant.agent.service.AgentExecutionGuardService.class);
        when(orders.queryOrder(eq("ORD-001"), eq("buyer@example.com"))).thenReturn(
                new com.omnimerchant.agent.tool.OrderTools.OrderQueryResult("ORD-001", "shipped", true, List.of(),
                        "79", "USD", "TRACK001", "USPS", "exception", null, "verified"));
        when(logistics.trackLogistics("TRACK001")).thenReturn(new com.omnimerchant.agent.tool.LogisticsTools.LogisticsResult(
                "TRACK001", "exception", null, List.of(Map.of("status", "exception")), "verified"));
        when(policies.refundPolicyRAG(anyString())).thenReturn(com.omnimerchant.knowledge.dto.PolicyAnswer.of(
                "FIXTURE policy: shipment disputes can be submitted for human review.",
                List.of(new com.omnimerchant.knowledge.dto.PolicyAnswer.Citation("chunk-1", "doc-1", 0, "policy", 1, 1)), "SUFFICIENT", null));
        when(orders.requestRefundOrReplacement(anyString(), anyString(), anyString(), anyString())).thenReturn(
                new com.omnimerchant.agent.service.CommercePlatformService.ReturnActionResult("ACT-TEST", "PENDING_HUMAN_APPROVAL", "REFUND", "ORD-001", "pending"));
        when(guard.executeA2aApproval(anyLong(), anyString(), anyString(), anyString(), any()))
                .thenAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(4)).get());
        var realSpecialists = new A2aSpecialists(orders, logistics, policies,
                mock(com.omnimerchant.agent.router.ModelRouter.class), repository, properties, guard);
        doAnswer(call -> realSpecialists.execute(call.getArgument(0), call.getArgument(1), call.getArgument(2)))
                .when(specialists).execute(any(), any(), any());
        var result = supervisor.chat(1, "conversation-1", "包裹未收到，订单 ORD-001 buyer@example.com，请申请退款", "trace-1")
                .collectList().block(java.time.Duration.ofSeconds(10));
        assertNotNull(result);
        assertTrue(result.stream().anyMatch(event -> "final".equals(event.type()) && event.data().contains("尚未执行")));
        assertTrue(result.stream().anyMatch(event -> "final".equals(event.type()) && event.data().contains("ACT-TEST")));
        var repeated = supervisor.chat(1, "conversation-1", "包裹未收到，订单 ORD-001 buyer@example.com，请申请退款", "trace-2")
                .collectList().block(java.time.Duration.ofSeconds(10));
        assertNotNull(repeated);
        assertTrue(repeated.stream().anyMatch(event -> "final".equals(event.type()) && event.data().contains("ACT-TEST")));
        verify(specialists, times(3)).execute(any(), any(), any());
        verify(orders, times(1)).requestRefundOrReplacement(eq("ORD-001"), eq("buyer@example.com"), eq("refund"), anyString());
        assertEquals(3, repository.summaries(1, "conversation-1").size());
        assertTrue(repository.summaries(2, "conversation-1").isEmpty());
    }
    @Test void concurrentDuplicateMessagesCreateAndExecuteOneTask() throws Exception {
        var message = message("concurrent", null, "buyer@example.com");
        var futures = new ArrayList<CompletableFuture<Task>>();
        for (int i = 0; i < 8; i++) futures.add(CompletableFuture.supplyAsync(() -> service.send(scope("order-logistics"), message)));
        var ids = new HashSet<String>();
        for (var future : futures) ids.add(future.get(5, TimeUnit.SECONDS).id());
        assertEquals(1, ids.size());
        var transport = transport("order-logistics");
        try {
            assertEquals(TASK_STATE_COMPLETED, await(transport, repository.get(scope("order-logistics"), ids.iterator().next()), context(1, "conversation-1", "order-logistics")).status().state());
        } finally { transport.close(); }
        verify(specialists, times(1)).execute(any(), any(), any());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM a2a_task", Integer.class));
    }
}
