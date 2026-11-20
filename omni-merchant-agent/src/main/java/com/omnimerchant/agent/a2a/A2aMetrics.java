package com.omnimerchant.agent.a2a;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class A2aMetrics {
    private final ObjectProvider<MeterRegistry> registries;
    public A2aMetrics(ObjectProvider<MeterRegistry> registries) { this.registries = registries; }
    public void count(String agent, String outcome) {
        registries.ifAvailable(registry -> registry.counter("omnimerchant.a2a.requests", "agent", agent, "outcome", outcome).increment());
    }
    public void duration(String agent, long millis) {
        registries.ifAvailable(registry -> registry.timer("omnimerchant.a2a.duration", "agent", agent)
                .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS));
    }
}
