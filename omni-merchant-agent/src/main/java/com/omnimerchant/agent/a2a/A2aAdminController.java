package com.omnimerchant.agent.a2a;

import com.omnimerchant.common.dto.R;
import com.omnimerchant.tenant.context.TenantContextHolder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/agent/a2a")
public class A2aAdminController {
    private final A2aConfiguration.Properties properties;
    private final A2aTaskRepository repository;
    public A2aAdminController(A2aConfiguration.Properties properties, A2aTaskRepository repository) {
        this.properties = properties; this.repository = repository;
    }
    @GetMapping("/status")
    public R<?> status() {
        requireTenant();
        return R.ok(Map.of("enabled", properties.isEnabled(), "mode", properties.isFixture() ? "FIXTURE" : "LIVE",
                "protocol", "1.0", "sdk", "1.5.0.Final", "agents", List.of("supervisor", "order-logistics", "policy", "after-sales")));
    }
    @GetMapping("/tasks")
    public R<?> tasks(@RequestParam String conversationUuid) {
        return R.ok(repository.summaries(requireTenant(), conversationUuid));
    }
    private long requireTenant() {
        var tenant = TenantContextHolder.get();
        if (tenant == null) throw new SecurityException("Verified tenant required");
        return tenant;
    }
}
