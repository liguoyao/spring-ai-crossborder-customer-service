package com.omnimerchant.agent.a2a;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(A2aConfiguration.Properties.class)
public class A2aConfiguration {
    @Data
    @ConfigurationProperties("agent.a2a")
    public static class Properties {
        private boolean enabled;
        private boolean fixture;
        private String secret = "";
        private String baseUrl = "http://127.0.0.1:8090";
        private Map<String, String> endpoints = Map.of();

        public String endpoint(String agent) {
            if (!java.util.Set.of("order-logistics", "policy", "after-sales").contains(agent)) {
                throw new IllegalArgumentException("Unknown agent");
            }
            var value = endpoints.getOrDefault(agent, baseUrl + "/api/a2a/" + agent);
            var uri = java.net.URI.create(value);
            if (!java.util.Set.of("http", "https").contains(
                uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("Invalid configured agent endpoint");
            }
            return value;
        }
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService a2aAgentExecutor() {
        return pool("a2a-agent-", 8, 32);
    }

    @Bean(destroyMethod = "shutdownNow")
    public ExecutorService a2aStreamExecutor() {
        return pool("a2a-stream-", 8, 32);
    }

    private ExecutorService pool(String name, int size, int queue) {
        return new ThreadPoolExecutor(size, size, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
            Thread.ofPlatform().name(name, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
}
