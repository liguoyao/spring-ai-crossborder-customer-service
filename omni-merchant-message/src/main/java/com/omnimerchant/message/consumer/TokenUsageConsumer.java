package com.omnimerchant.message.consumer;

import com.omnimerchant.common.event.TokenUsageEvent;
import com.omnimerchant.common.mq.RocketMQClientFactory;
import com.omnimerchant.tenant.entity.TokenUsageDaily;
import com.omnimerchant.tenant.mapper.TokenUsageDailyMapper;
import com.omnimerchant.tenant.context.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Consumes token usage events from RocketMQ Proxy (gRPC) and persists to token_usage_daily table.
 * Implements upsert: first event of the day inserts, subsequent events update the rollup.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "omnimerchant.message", name = "enabled", havingValue = "true", matchIfMissing = true)
public class TokenUsageConsumer implements MessageListener, InitializingBean {

    private final TokenUsageDailyMapper mapper;
    private final RocketMQClientFactory clientFactory;

    public TokenUsageConsumer(TokenUsageDailyMapper mapper, RocketMQClientFactory clientFactory) {
        this.mapper = mapper;
        this.clientFactory = clientFactory;
    }

    @Override
    public void afterPropertiesSet() {
        if (!clientFactory.isEnabled()) {
            log.info("RocketMQ client disabled, token usage consumer will not start");
            return;
        }
        Thread.ofVirtual().name("rocketmq-consumer-starter").start(() -> {
            try {
                clientFactory.startConsumer(this);
            } catch (Exception e) {
                log.warn("Failed to start RocketMQ push consumer via proxy: {}", e.getMessage());
            }
        });
    }

    @Override
    public ConsumeResult consume(MessageView messageView) {
        TokenUsageEvent event;
        try {
            event = clientFactory.deserialize(messageView.getBody(), TokenUsageEvent.class);
        } catch (Exception e) {
            log.warn("Discard malformed token usage message: {}", e.getMessage());
            return ConsumeResult.SUCCESS;
        }
        if (event == null || event.getTenantId() == null) {
            log.warn("Discard token usage message without tenant ID");
            return ConsumeResult.SUCCESS;
        }
        var previousTenant = TenantContextHolder.get();
        try {
            TenantContextHolder.set(event.getTenantId());
            persist(event);
            return ConsumeResult.SUCCESS;
        } catch (Exception e) {
            log.error("Failed to persist token usage event: tenant={}, model={}, error={}",
                    event.getTenantId(), event.getModelName(), e.getMessage());
            return ConsumeResult.FAILURE;
        } finally {
            if (previousTenant == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.set(previousTenant);
            }
        }
    }

    private void persist(TokenUsageEvent event) {
        var usageDate = event.getTimestamp() != null
                ? LocalDate.ofInstant(event.getTimestamp().atZone(ZoneId.systemDefault()).toInstant(),
                      ZoneId.systemDefault())
                : LocalDate.now();

        var modelName = event.getModelName() != null ? event.getModelName() : "unknown";

        var existing = mapper.selectByTenantAndDate(event.getTenantId(), usageDate, modelName);
        if (existing != null) {
            existing.setPromptTokens(
                    (existing.getPromptTokens() != null ? existing.getPromptTokens() : 0L)
                            + event.getPromptTokens());
            existing.setCompletionTokens(
                    (existing.getCompletionTokens() != null ? existing.getCompletionTokens() : 0L)
                            + event.getCompletionTokens());
            existing.setTotalTokens(
                    (existing.getTotalTokens() != null ? existing.getTotalTokens() : 0L)
                            + event.getPromptTokens() + event.getCompletionTokens());
            existing.setRequestCount(
                    (existing.getRequestCount() != null ? existing.getRequestCount() : 0) + 1);
            existing.setTotalLatencyMs(
                    (existing.getTotalLatencyMs() != null ? existing.getTotalLatencyMs() : 0L)
                            + event.getLatencyMs());
            mapper.updateById(existing);
            log.debug("Updated token usage: tenant={}, date={}, model={}, totalTokens={}",
                    event.getTenantId(), usageDate, modelName, existing.getTotalTokens());
        } else {
            var record = new TokenUsageDaily();
            record.setTenantId(event.getTenantId());
            record.setUsageDate(usageDate);
            record.setModelName(modelName);
            record.setModelProvider(TokenUsageEvent.resolveProviderName(event.getProviderName(), modelName));
            record.setPromptTokens((long) event.getPromptTokens());
            record.setCompletionTokens((long) event.getCompletionTokens());
            record.setTotalTokens((long) (event.getPromptTokens() + event.getCompletionTokens()));
            record.setRequestCount(1);
            record.setSuccessCount(1);
            record.setTotalLatencyMs(event.getLatencyMs());
            mapper.insert(record);
            log.debug("Inserted token usage: tenant={}, date={}, model={}, totalTokens={}",
                    event.getTenantId(), usageDate, modelName, record.getTotalTokens());
        }
    }
}
