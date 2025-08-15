package com.example.broadcast.shared.config;

import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.util.List;

@Configuration
public class RedisConfig {

    @Bean("dltTestRedisTemplate")
    public RedisTemplate<String, String> dltTestRedisTemplate(RedisConnectionFactory connectionFactory) {
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(connectionFactory);
        return template;
    }

    @Bean
    public RedisTemplate<String, UserConnectionInfo> userConnectionInfoRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, UserConnectionInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<UserConnectionInfo> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, UserConnectionInfo.class);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(serializer);
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