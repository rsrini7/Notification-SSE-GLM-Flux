package com.example.broadcast.shared.config;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage; // Import BroadcastMessage
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

    @Bean
    public RedisTemplate<String, BroadcastMessage> broadcastMessageRedisTemplate(RedisConnectionFactory connectionFactory, ObjectMapper objectMapper) {
        RedisTemplate<String, BroadcastMessage> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        Jackson2JsonRedisSerializer<BroadcastMessage> serializer = new Jackson2JsonRedisSerializer<>(objectMapper, BroadcastMessage.class);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(serializer);
        return template;
    }
}