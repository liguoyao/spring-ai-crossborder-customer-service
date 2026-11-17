package com.omnimerchant.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token usage event sent via RocketMQ Proxy (gRPC) for async persistence.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsageEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long tenantId;
    private String tenantCode;
    private String modelName;
    private String providerName;
    private int promptTokens;
    private int completionTokens;
    private long latencyMs;
    private String intent;
    private String conversationUuid;
    private LocalDateTime timestamp;

    /** Backward compatibility for messages queued before providerName was populated. */
    public static String resolveProviderName(String provider, String model) {
        if (provider != null && !provider.isBlank()) return provider.trim();
        var name = model == null ? "" : model.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("deepseek")) return "deepseek";
        if (name.startsWith("qwen") || name.startsWith("text-embedding-v")) return "dashscope";
        return "unknown";
    }
}
