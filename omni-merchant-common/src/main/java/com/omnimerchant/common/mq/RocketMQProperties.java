package com.omnimerchant.common.mq;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Connection settings for the RocketMQ 5.x gRPC client.
 * The endpoints point at Proxy (gRPC) instead of NameServer (remoting).
 */
@Data
@ConfigurationProperties(prefix = "omnimerchant.rocketmq")
public class RocketMQProperties {

    private boolean enabled = true;

    /** Proxy gRPC endpoints, e.g. {@code 127.0.0.1:8081}; multiple addresses are separated by {@code ;}. */
    private String endpoints = "localhost:8081";

    /** Optional ACL credentials; left empty for a local Proxy without ACL. */
    private String accessKey = "";

    private String secretKey = "";

    private boolean sslEnabled = false;

    private Duration requestTimeout = Duration.ofSeconds(10);

    private String topic = "omni-token-usage";

    private String tag = "daily";

    private String consumerGroup = "omni-token-usage-consumer";
}
