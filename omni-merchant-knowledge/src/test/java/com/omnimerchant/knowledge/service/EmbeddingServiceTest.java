package com.omnimerchant.knowledge.service;

import com.omnimerchant.common.config.OmniMerchantProperties;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmbeddingServiceTest {
    @SuppressWarnings("unchecked")
    private ObjectProvider<EmbeddingModel> provider(EmbeddingModel model) {
        ObjectProvider<EmbeddingModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }

    @Test
    void splitsLongInputIntoBatchesOfTenAndPreservesOrder() {
        var model = mock(EmbeddingModel.class);
        when(model.embed(anyList())).thenAnswer(invocation -> {
            List<String> inputs = invocation.getArgument(0);
            return inputs.stream().map(text -> {
                var vector = new float[1536];
                vector[0] = Integer.parseInt(text);
                return vector;
            }).toList();
        });
        var props = new OmniMerchantProperties();
        props.getKnowledge().getEmbedding().setBatchSize(20);
        var service = new EmbeddingService(provider(model), mock(StringRedisTemplate.class), props);
        var inputs = java.util.stream.IntStream.range(0, 23).mapToObj(Integer::toString).toList();
        var result = service.embedBatch(inputs);
        assertThat(result).hasSize(23);
        for (int i = 0; i < 23; i++) assertThat(result.get(i)[0]).isEqualTo(i);
        verify(model).embed(inputs.subList(0, 10));
        verify(model).embed(inputs.subList(10, 20));
        verify(model).embed(inputs.subList(20, 23));
    }

    @Test
    void rejectsWrongDimensionsAndResponseCount() {
        var model = mock(EmbeddingModel.class);
        var service = new EmbeddingService(provider(model), mock(StringRedisTemplate.class),
                new OmniMerchantProperties());
        when(model.embed(anyList())).thenReturn(List.of(new float[1024]));
        assertThatThrownBy(() -> service.embedBatch(List.of("a")))
                .hasMessageContaining("1536 dimensions");
        when(model.embed(anyList())).thenReturn(List.of());
        assertThatThrownBy(() -> service.embedBatch(List.of("a")))
                .hasMessageContaining("count");
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheSeparatesModelEndpointVersionAndTenantAndIgnoresOldNamespace() {
        var model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(new float[1536]);
        var redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        var props = new OmniMerchantProperties();
        var service = new EmbeddingService(provider(model), redis, props);
        TenantContextHolder.set(1L);
        service.embed("same text");
        props.getKnowledge().getEmbedding().setModel("different-model");
        service.embed("same text");
        props.getKnowledge().getEmbedding().setBaseUrl("https://different-endpoint");
        service.embed("same text");
        props.getKnowledge().getEmbedding().setCacheVersion("v3");
        service.embed("same text");
        TenantContextHolder.set(2L);
        service.embed("same text");
        var keys = ArgumentCaptor.forClass(String.class);
        verify(values, times(5)).get(keys.capture());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates()
                .allMatch(key -> key.startsWith("omni:embed:v2:"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void cacheSerializationWorksWithCommaDecimalLocale() {
        var original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            TenantContextHolder.set(1L);
            var vector = new float[1536];
            vector[0] = 0.125f;
            var model = mock(EmbeddingModel.class);
            when(model.embed("text")).thenReturn(vector);
            var redis = mock(StringRedisTemplate.class);
            ValueOperations<String, String> values = mock(ValueOperations.class);
            when(redis.opsForValue()).thenReturn(values);
            var cached = new ArrayList<String>();
            doAnswer(call -> { cached.add(call.getArgument(1)); return null; })
                    .when(values).set(anyString(), anyString(), any(java.time.Duration.class));
            var service = new EmbeddingService(provider(model), redis, new OmniMerchantProperties());
            service.embed("text");
            when(values.get(anyString())).thenReturn(cached.getFirst());
            assertThat(service.embed("text")).containsExactly(vector);
            verify(model, times(1)).embed("text");
        } finally {
            Locale.setDefault(original);
        }
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    void shouldRejectEmbeddingWithoutTenantContext() {
        TenantContextHolder.clear();
        @SuppressWarnings("unchecked")
        ObjectProvider<EmbeddingModel> embeddingModelProvider = mock(ObjectProvider.class);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(mock(EmbeddingModel.class));
        var service = new EmbeddingService(
                embeddingModelProvider,
                mock(StringRedisTemplate.class),
                new OmniMerchantProperties());

        assertThatThrownBy(() -> service.embed("refund policy"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing tenant context");
    }
}
