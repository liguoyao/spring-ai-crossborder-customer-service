package com.omnimerchant.agent.router;

import com.omnimerchant.agent.advisor.TokenUsageAdvisor;
import com.omnimerchant.agent.ratelimit.RateLimitedChatModel;
import com.omnimerchant.agent.ratelimit.TokenRateLimiter;
import lombok.extern.slf4j.Slf4j;
import com.omnimerchant.common.config.OmniMerchantProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Routes requests to the optimal chat model based on intent type and complexity.
 * All models are wrapped with RateLimitedChatModel for 3-layer rate limiting.
 */
@Slf4j
@Service
public class ModelRouter {

    private final RoutedModel dashScopeModel;
    private final RoutedModel deepSeekModel;

    public ModelRouter(
            @Qualifier("dashScopeChatModel") ObjectProvider<OpenAiChatModel> dashScopeChatModel,
            @Qualifier("deepSeekChatModel") ObjectProvider<OpenAiChatModel> deepSeekChatModel,
            OmniMerchantProperties props,
            TokenRateLimiter rateLimiter,
            TokenUsageAdvisor tokenUsageAdvisor) {
        this.dashScopeModel = wrap(dashScopeChatModel.getIfAvailable(), rateLimiter, tokenUsageAdvisor,
                "dashscope", props.getLlm().getDashscope().getModel());
        this.deepSeekModel = wrap(deepSeekChatModel.getIfAvailable(), rateLimiter, tokenUsageAdvisor,
                "deepseek", props.getLlm().getDeepseek().getModel());
    }

    private RoutedModel wrap(ChatModel model, TokenRateLimiter rateLimiter,
                             TokenUsageAdvisor tokenUsageAdvisor,
                             String providerName, String modelName) {
        if (model == null) {
            log.warn("Chat model disabled: provider={}, model={}. Configure the provider API key to enable it.",
                    providerName, modelName);
            return new RoutedModel(null, providerName, modelName);
        }
        return new RoutedModel(new RateLimitedChatModel(model, rateLimiter, tokenUsageAdvisor, modelName),
                providerName, modelName);
    }

    public RoutedModel route(String intent) {
        var model = selectModel(intent);
        log.info("ModelRouter: intent={} -> model={}, available={}", intent, model.modelName(), model.available());
        return model;
    }

    public RoutedModel fallback() {
        return firstAvailable(deepSeekModel, dashScopeModel);
    }

    private RoutedModel selectModel(String intent) {
        if (intent == null) {
            return firstAvailable(deepSeekModel, dashScopeModel);
        }
        var preferred = switch (intent.toUpperCase()) {
            case "COMPLAINT", "ESCALATION" ->
                    dashScopeModel;
            case "ORDER_QUERY", "LOGISTICS_TRACKING",
                 "REFUND_POLICY", "PRODUCT_INQUIRY",
                 "GREETING", "UNCLEAR" ->
                    deepSeekModel;
            default ->
                    deepSeekModel;
        };
        return firstAvailable(preferred, deepSeekModel, dashScopeModel);
    }

    private RoutedModel firstAvailable(RoutedModel... candidates) {
        for (var candidate : candidates) {
            if (candidate != null && candidate.available()) {
                return candidate;
            }
        }
        return new RoutedModel(null, "none", "unconfigured");
    }

    public record RoutedModel(ChatModel chatModel, String providerName, String modelName) {
        public boolean available() {
            return chatModel != null;
        }
    }
}
