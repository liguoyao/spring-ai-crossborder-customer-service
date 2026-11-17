package com.omnimerchant.config;

import com.omnimerchant.common.config.OmniMerchantProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** DeepSeek and DashScope use the OpenAI-compatible chat API. */
@Configuration
public class LlmConfiguration {

    private static final Double TEMPERATURE = 0.3;

    @Bean
    @ConditionalOnExpression("'${omnimerchant.llm.dashscope.api-key:}' != ''")
    public OpenAiChatModel dashScopeChatModel(OmniMerchantProperties props) {
        var ds = props.getLlm().getDashscope();
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(ds.getBaseUrl())
                        .apiKey(ds.getApiKey())
                        .model(ds.getModel())
                        .temperature(TEMPERATURE)
                        .maxTokens(4096)
                        .build())
                .build();
    }


    /** DeepSeek and DashScope use the OpenAI-compatible chat API. */
    @Bean
    @ConditionalOnExpression("'${omnimerchant.llm.deepseek.api-key:}' != ''")
    public OpenAiChatModel deepSeekChatModel(OmniMerchantProperties props) {
        var ds = props.getLlm().getDeepseek();
        return OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(ds.getBaseUrl())
                        .apiKey(ds.getApiKey())
                        .model(ds.getModel())
                        .temperature(TEMPERATURE)
                        .maxTokens(4096)
                        .build())
                .build();
    }

    /** Shared circuit breaker registry. */
    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.ofDefaults();
    }

    /** Circuit breaker for LLM calls. */
    @Bean
    public CircuitBreaker llmCircuitBreaker(CircuitBreakerRegistry registry) {
        return registry.circuitBreaker("llm");
    }
}
