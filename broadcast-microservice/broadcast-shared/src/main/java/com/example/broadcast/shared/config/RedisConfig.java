// UPDATED FILE: broadcast-microservice/broadcast-shared/src/main/java/com/example/broadcast/shared/config/RedisConfig.java
package com.example.broadcast.shared.config;

import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.List;
import java.util.concurrent.Executor; 

@Configuration
public class RedisConfig {

    @Bean("dltTestRedisTemplate")
    public RedisTemplate<String, String> dltTestRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    // ADDED: A dedicated RedisTemplate for Pub/Sub using String serializers for simplicity
    @Bean("pubSubRedisTemplate")
    public RedisTemplate<String, String> pubSubRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    // ADDED: The listener container that manages subscriptions and message dispatching
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory, Executor redisTaskExecutor) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisTaskExecutor);
        return container;
    }

    // ADDED: A dedicated thread pool for the Redis listeners to prevent blocking
    @Bean
    public Executor redisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(20);
        executor.setQueueCapacity(1000);
        executor.setThreadNamePrefix("redis-listener-");
        executor.initialize();
        return executor;
    }

    @Bean
    public RedisTemplate<String, Object> genericRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        // Use standard string serializer for hash values to store them as JSON strings
        template.setHashValueSerializer(new StringRedisSerializer());
        return template;
    }

    @Bean
    public RedisTemplate<String, List<PersistentUserMessageInfo>> persistentUserMessagesRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<PersistentUserMessageInfo>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, PersistentUserMessageInfo.class);
        Jackson2JsonRedisSerializer<List<PersistentUserMessageInfo>> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, type);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }

    @Bean
    public RedisTemplate<String, List<PendingEventInfo>> pendingEventsRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<PendingEventInfo>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, PendingEventInfo.class);
        Jackson2JsonRedisSerializer<List<PendingEventInfo>> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, type);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }

    @Bean
    public RedisTemplate<String, BroadcastMessage> broadcastMessageRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, BroadcastMessage> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<BroadcastMessage> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, BroadcastMessage.class);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }

    @Bean
    public RedisTemplate<String, List<BroadcastMessage>> activeGroupBroadcastsRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<BroadcastMessage>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);

        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, BroadcastMessage.class);
        Jackson2JsonRedisSerializer<List<BroadcastMessage>> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, type);

        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }
}