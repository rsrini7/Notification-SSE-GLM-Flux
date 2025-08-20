package com.example.broadcast.user.config;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.user.service.RedisMessageListener;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import jakarta.annotation.PostConstruct;

@Configuration
@RequiredArgsConstructor
public class UserRedisConfig {

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final RedisMessageListener redisMessageListener;
    private final AppProperties appProperties;

    @PostConstruct
    public void setupRedisListeners() {
        String podId = appProperties.getPod().getId();
        String clusterName = appProperties.getClusterName();
        
        String channelName = "notifications:" + clusterName + ":" + podId;
        ChannelTopic topic = new ChannelTopic(channelName);
        redisMessageListenerContainer.addMessageListener(redisMessageListener, topic);
    }
}