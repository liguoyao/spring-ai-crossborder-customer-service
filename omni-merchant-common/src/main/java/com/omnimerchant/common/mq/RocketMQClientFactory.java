package com.omnimerchant.common.mq;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientConfigurationBuilder;
import org.apache.rocketmq.client.apis.ClientException;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.StaticSessionCredentialsProvider;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.message.MessageBuilder;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;

/**
 * Shared RocketMQ 5.x gRPC client factory. All producers and consumers connect to
 * the Proxy endpoints declared by {@code omnimerchant.rocketmq.endpoints}; no
 * NameServer address is used any more. Clients are created lazily so the
 * application can still start when the Proxy is unavailable.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RocketMQProperties.class)
public class RocketMQClientFactory implements DisposableBean {

    private final RocketMQProperties properties;
    private final ClientServiceProvider provider;
    private final ObjectMapper objectMapper;

    private volatile Producer producer;
    private volatile PushConsumer consumer;

    public RocketMQClientFactory(RocketMQProperties properties) {
        this.properties = properties;
        this.provider = ClientServiceProvider.loadService();
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public String topic() {
        return properties.getTopic();
    }

    public String tag() {
        return properties.getTag();
    }

    public MessageBuilder newMessageBuilder() {
        return provider.newMessageBuilder();
    }

    public byte[] serialize(Object payload) throws IOException {
        return objectMapper.writeValueAsBytes(payload);
    }

    public <T> T deserialize(ByteBuffer body, Class<T> type) throws IOException {
        byte[] bytes = new byte[body.remaining()];
        body.get(bytes);
        return objectMapper.readValue(bytes, type);
    }

    public Producer producer() throws ClientException {
        Producer current = producer;
        if (current == null) {
            synchronized (this) {
                current = producer;
                if (current == null) {
                    current = provider.newProducerBuilder()
                            .setClientConfiguration(clientConfiguration())
                            .setTopics(properties.getTopic())
                            .build();
                    producer = current;
                    log.info("RocketMQ producer started via proxy endpoints={}, topic={}",
                            properties.getEndpoints(), properties.getTopic());
                }
            }
        }
        return current;
    }

    public PushConsumer startConsumer(MessageListener listener) throws ClientException {
        PushConsumer current = consumer;
        if (current == null) {
            synchronized (this) {
                current = consumer;
                if (current == null) {
                    FilterExpression filter = properties.getTag() == null || properties.getTag().isBlank()
                            ? FilterExpression.SUB_ALL
                            : new FilterExpression(properties.getTag(), FilterExpressionType.TAG);
                    current = provider.newPushConsumerBuilder()
                            .setClientConfiguration(clientConfiguration())
                            .setConsumerGroup(properties.getConsumerGroup())
                            .setSubscriptionExpressions(Collections.singletonMap(properties.getTopic(), filter))
                            .setMessageListener(listener)
                            .build();
                    consumer = current;
                    log.info("RocketMQ push consumer started via proxy endpoints={}, topic={}, group={}",
                            properties.getEndpoints(), properties.getTopic(), properties.getConsumerGroup());
                }
            }
        }
        return current;
    }

    private ClientConfiguration clientConfiguration() {
        ClientConfigurationBuilder builder = ClientConfiguration.newBuilder()
                .setEndpoints(properties.getEndpoints())
                .enableSsl(properties.isSslEnabled())
                .setRequestTimeout(properties.getRequestTimeout());
        if (properties.getAccessKey() != null && !properties.getAccessKey().isBlank()
                && properties.getSecretKey() != null && !properties.getSecretKey().isBlank()) {
            builder.setCredentialProvider(new StaticSessionCredentialsProvider(
                    properties.getAccessKey(), properties.getSecretKey()));
        }
        return builder.build();
    }

    @Override
    public void destroy() {
        closeQuietly(consumer, "consumer");
        closeQuietly(producer, "producer");
    }

    private void closeQuietly(Closeable client, String name) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (IOException e) {
            log.warn("Failed to close RocketMQ {}: {}", name, e.getMessage());
        }
    }
}
