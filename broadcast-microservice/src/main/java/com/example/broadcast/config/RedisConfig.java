package com.example.broadcast.config;

import com.example.broadcast.dto.cache.*;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.util.List;

@Configuration
@Profile("redis")
public class RedisConfig {

    // START OF CHANGE: The conflicting bean has been removed.
    // Spring Boot's RedisAutoConfiguration will now provide the 'stringRedisTemplate' bean.
    /*
    @Bean
    public RedisTemplate<String, String> stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        RedisTemplate<String, String> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setValueSerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);
        template.setHashValueSerializer(stringSerializer);
        return template;
    }
    */
    // END OF CHANGE

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
    public RedisTemplate<String, List<UserMessageInfo>> userMessagesRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, List<UserMessageInfo>> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        
        JavaType type = objectMapper.getTypeFactory().constructCollectionType(List.class, UserMessageInfo.class);
        Jackson2JsonRedisSerializer<List<UserMessageInfo>> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, type);

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
    public RedisTemplate<String, UserSessionInfo> userSessionRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, UserSessionInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<UserSessionInfo> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, UserSessionInfo.class);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }

    @Bean
    public RedisTemplate<String, BroadcastStatsInfo> broadcastStatsRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, BroadcastStatsInfo> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<BroadcastStatsInfo> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, BroadcastStatsInfo.class);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }
}