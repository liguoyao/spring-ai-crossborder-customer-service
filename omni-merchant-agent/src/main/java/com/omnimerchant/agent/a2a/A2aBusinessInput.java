package com.omnimerchant.agent.a2a;

/** Business contract v1. Evidence is resolved server-side from authorized task references. */
public record A2aBusinessInput(int version, String orderId, String customerIdentity, String action, String question,
                               String orderTaskId, String policyTaskId, String traceId, String parentTaskId,
                               long deadlineEpochMs) {
    public A2aBusinessInput {
        if (version != 1 || question == null || question.length() > 8000 || orderId != null && orderId.length() > 64 || customerIdentity != null && customerIdentity.length() > 254 || action != null && !java.util.Set.of(
            "refund", "replacement").contains(action)) {
            throw new IllegalArgumentException("Invalid A2A business input");
        }
    }
}
