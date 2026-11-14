package com.omnimerchant.agent.advisor;

import com.omnimerchant.common.event.TokenUsageEvent;
import com.omnimerchant.common.mq.RocketMQClientFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.Message;
import org.springframework.stereotype.Component;

/**
 * Sends token usage events to RocketMQ Proxy (gRPC) for async persistence.
 * Fire-and-forget — failures are logged but never block the response path.
 */
@Slf4j
@Component
public class TokenUsageProducer {

    private final RocketMQClientFactory clientFactory;

    public TokenUsageProducer(RocketMQClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public void sendAsync(TokenUsageEvent event) {
        if (!clientFactory.isEnabled()) {
            log.debug("RocketMQ client disabled, skipping token usage event");
            return;
        }
        try {
            Message message = clientFactory.newMessageBuilder()
                    .setTopic(clientFactory.topic())
                    .setTag(clientFactory.tag())
                    .setKeys(String.valueOf(event.getTenantId()))
                    .setBody(clientFactory.serialize(event))
                    .build();
            clientFactory.producer().sendAsync(message).whenComplete((receipt, throwable) -> {
                if (throwable != null) {
                    log.warn("Failed to send token usage event: tenant={}, model={}, error={}",
                            event.getTenantId(), event.getModelName(), throwable.getMessage());
                } else {
                    log.debug("Token usage event sent: tenant={}, model={}, tokens={}, messageId={}",
                            event.getTenantId(), event.getModelName(),
                            event.getPromptTokens() + event.getCompletionTokens(), receipt.getMessageId());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to enqueue token usage event: {}", e.getMessage());
        }
    }
}
