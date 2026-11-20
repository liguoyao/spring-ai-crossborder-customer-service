package com.omnimerchant.agent.a2a;

import com.omnimerchant.agent.router.ModelRouter;
import com.omnimerchant.agent.service.AgentExecutionGuardService;
import com.omnimerchant.agent.service.CommercePlatformService.ReturnActionResult;
import com.omnimerchant.agent.tool.*;
import com.omnimerchant.knowledge.dto.PolicyAnswer;
import com.omnimerchant.knowledge.tool.PolicyTools;
import org.a2aproject.sdk.spec.*;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.omnimerchant.agent.a2a.A2aTaskRepository.*;
import static org.a2aproject.sdk.spec.TaskState.*;

class A2aBusinessTest {
    OrderTools orders = mock(OrderTools.class);
    LogisticsTools logistics = mock(LogisticsTools.class);
    PolicyTools policies = mock(PolicyTools.class);
    ModelRouter models = mock(ModelRouter.class);
    A2aTaskRepository repository = mock(A2aTaskRepository.class);
    AgentExecutionGuardService guard = mock(AgentExecutionGuardService.class);
    A2aConfiguration.Properties properties = new A2aConfiguration.Properties();
    A2aSpecialists specialists;
    @BeforeEach void setup() {
        properties.setFixture(true);
        specialists = new A2aSpecialists(orders, logistics, policies, models, repository, properties, guard);
    }
    Task task(String id) { return new Task(id, "conversation", new TaskStatus(TASK_STATE_WORKING), List.of(), List.of(), Map.of()); }
    A2aIdentity.Scope scope(String agent) { return new A2aIdentity.Scope(1, "conversation", agent, "supervisor"); }
    A2aBusinessInput input(String action) { return new A2aBusinessInput(1, "ORD-001", "buyer@example.com", action,
            "包裹未收到，申请退款", "order-task", "policy-task", "trace", "policy-task", System.currentTimeMillis()+60000); }
    @Test void unverifiedIdentityNeverExposesOrderFactsOrQueriesTracking() {
        when(orders.queryOrder(anyString(), anyString())).thenReturn(new OrderTools.OrderQueryResult("ORD-001", "IDENTITY_VERIFICATION_REQUIRED", false,
                List.of(), null, null, null, null, null, null, "verify"));
        var output = specialists.execute(scope("order-logistics"), task("order-task"), input("refund"));
        assertEquals(TASK_STATE_INPUT_REQUIRED, output.status().state());
        assertFalse(data(output).containsKey("facts")); verifyNoInteractions(logistics);
    }
    @Test void highValueOrderIsRoutedToHumanWithoutRequestingApproval() {
        when(orders.queryOrder(anyString(), anyString())).thenReturn(new OrderTools.OrderQueryResult("ORD-001", "SHIPPED", true,
                List.of(), "101", "USD", "TRACK001", "DHL", "in_transit", null, "verified"));
        assertEquals(TASK_STATE_REJECTED, specialists.execute(scope("order-logistics"), task("order-task"), input("refund")).status().state());
        verifyNoInteractions(logistics, guard);
    }
    @Test void policyRefusesAbsentCitations() {
        when(repository.get(eq(scope("order-logistics")), eq("order-task"))).thenReturn(result(task("order-task"), TASK_STATE_COMPLETED, Map.of("facts", Map.of("verified", true))));
        when(policies.refundPolicyRAG(anyString())).thenReturn(PolicyAnswer.error("no evidence"));
        assertEquals(TASK_STATE_REJECTED, specialists.execute(scope("policy"), task("policy-task"), input("refund")).status().state());
        verifyNoInteractions(guard, models);
    }
    @Test void missingChoiceWaitsWithoutCallingAnyWriteTool() {
        assertEquals(TASK_STATE_INPUT_REQUIRED, specialists.execute(scope("after-sales"), task("sales-task"), input(null)).status().state());
        verifyNoInteractions(orders, guard, repository);
    }
    void evidence(boolean eligible) {
        when(repository.get(eq(scope("order-logistics")), eq("order-task"))).thenReturn(result(task("order-task"), TASK_STATE_COMPLETED, Map.of("facts", Map.of("verified", true))));
        when(repository.get(eq(scope("policy")), eq("policy-task"))).thenReturn(result(task("policy-task"), TASK_STATE_COMPLETED, Map.of("eligible", eligible, "orderTaskId", "order-task")));
        when(repository.input(eq(scope("order-logistics")), eq("order-task"))).thenReturn(input("refund"));
    }
    @Test void insufficientEvidenceNeverCreatesApproval() {
        evidence(false);
        assertEquals(TASK_STATE_REJECTED, specialists.execute(scope("after-sales"), task("sales-task"), input("refund")).status().state());
        verifyNoInteractions(guard, orders);
    }
    @Test void mismatchedOrderCannotReuseEvidence() {
        evidence(true);
        var mismatched = new A2aBusinessInput(1, "ORD-OTHER", "buyer@example.com", "refund", "question", "order-task", "policy-task", "trace", null, System.currentTimeMillis()+60000);
        assertThrows(SecurityException.class, () -> specialists.execute(scope("after-sales"), task("sales-task"), mismatched));
        verifyNoInteractions(orders, guard);
    }
    @Test void approvedEvidenceUsesExistingIdempotencyGuardAndOnlySubmitsHumanReview() {
        evidence(true);
        when(guard.executeA2aApproval(anyLong(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> ((java.util.function.Supplier<?>)call.getArgument(4)).get());
        when(orders.requestRefundOrReplacement(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new ReturnActionResult("ACT-001", "PENDING_HUMAN_APPROVAL", "REFUND", "ORD-001", "pending"));
        var output = specialists.execute(scope("after-sales"), task("sales-task"), input("refund"));
        assertEquals(TASK_STATE_COMPLETED, output.status().state());
        assertTrue(data(output).get("message").toString().contains("尚未执行"));
        verify(guard).executeA2aApproval(eq(1L), eq("conversation"), eq("trace"), anyString(), any());
    }
    @Test void duplicateGuardResultDoesNotClaimANewApproval() {
        evidence(true);
        when(guard.executeA2aApproval(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn("{\"status\":\"DUPLICATE_BLOCKED\"}");
        assertEquals(TASK_STATE_REJECTED, specialists.execute(scope("after-sales"), task("sales-task"), input("refund")).status().state());
        verifyNoInteractions(orders);
    }
    @Test void expiredDeadlineNeverCallsTools() {
        var expired = new A2aBusinessInput(1, "ORD-001", "buyer@example.com", "refund", "question", null, null, null, null, 1);
        assertEquals(TASK_STATE_FAILED, specialists.execute(scope("order-logistics"), task("task"), expired).status().state());
        verifyNoInteractions(orders, logistics, policies, guard);
    }
    @Test void supervisorParsesIdentityAndDoesNotTreatNegationOrAmbiguousChoicesAsConsent() {
        var first = A2aSupervisor.extract("订单 ORD-001 buyer@example.com 包裹未收到，请申请退款", null, "trace", 100);
        assertEquals("ORD-001", first.orderId()); assertEquals("buyer@example.com", first.customerIdentity()); assertEquals("refund", first.action());
        assertNull(A2aSupervisor.extract("包裹没到，不要退款，只查物流", first, "trace", 100).action());
        assertNull(A2aSupervisor.extract("包裹没到，退款还是补发？", null, "trace", 100).action());
        assertNull(A2aSupervisor.extract("包裹没到，退款政策是什么？", null, "trace", 100).action());
        assertEquals("replacement", A2aSupervisor.extract("补发", first, "trace", 100).action());
    }
    @Test void serviceTokensAreIsolatedFromOtherKeysAndBoundToAudience() {
        properties.setSecret("a-separate-test-signing-secret-with-more-than-32-bytes");
        var identity = new A2aIdentity(properties);
        var token = identity.issue(1, "conversation", "policy");
        assertEquals(scope("policy"), identity.verify("Bearer " + token, "policy"));
        assertThrows(SecurityException.class, () -> identity.verify("Bearer " + token, "after-sales"));
        var encrypted = identity.encrypt("buyer@example.com");
        assertFalse(encrypted.contains("buyer")); assertEquals("buyer@example.com", identity.decrypt(encrypted));
        var other = new A2aConfiguration.Properties(); other.setSecret("another-separate-test-signing-secret-with-32-bytes");
        assertThrows(SecurityException.class, () -> new A2aIdentity(other).verify("Bearer " + token, "policy"));
    }
    @Test void endpointConfigurationRejectsUnsupportedAgentsAndUnsafeUrls() {
        assertThrows(IllegalArgumentException.class, () -> properties.endpoint("https://attacker.example"));
        properties.setEndpoints(Map.of("policy", "http://user:password@example.com/policy"));
        assertThrows(IllegalArgumentException.class, () -> properties.endpoint("policy"));
    }
}
