package com.omnimerchant.config;

import com.omnimerchant.common.event.TokenUsageEvent;
import com.omnimerchant.common.mq.RocketMQClientFactory;
import com.omnimerchant.message.consumer.TokenUsageConsumer;
import com.omnimerchant.tenant.context.TenantContextHolder;
import com.omnimerchant.tenant.entity.TokenUsageDaily;
import com.omnimerchant.tenant.mapper.TokenUsageDailyMapper;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenUsageConsumerTest {
    @AfterEach void clear() { TenantContextHolder.clear(); }

    @Test
    void accumulatesInOneDatabaseOperationWithTenantAndProvider() throws Exception {
        var mapper = mock(TokenUsageDailyMapper.class);
        var factory = mock(RocketMQClientFactory.class);
        when(factory.deserialize(any(), eq(TokenUsageEvent.class))).thenReturn(
                TokenUsageEvent.builder().tenantId(1L).modelName("deepseek-flash")
                        .promptTokens(20).completionTokens(10).build());
        when(mapper.accumulate(any())).thenAnswer(call -> {
            TokenUsageDaily usage = call.getArgument(0);
            assertThat(TenantContextHolder.get()).isEqualTo(1L);
            assertThat(usage.getModelProvider()).isEqualTo("deepseek");
            assertThat(usage.getTotalTokens()).isEqualTo(30L);
            assertThat(usage.getRequestCount()).isEqualTo(1);
            return 2; // MySQL reports an existing row updated.
        });
        var consumer = new TokenUsageConsumer(mapper, factory);
        assertThat(consumer.consume(mock(MessageView.class))).isEqualTo(ConsumeResult.SUCCESS);
        assertThat(TenantContextHolder.get()).isNull();
        verify(mapper).accumulate(any());
        verifyNoMoreInteractions(mapper);
    }
}
