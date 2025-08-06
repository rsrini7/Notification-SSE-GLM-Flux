package com.example.broadcast.shared.config;

import com.example.broadcast.shared.dto.cache.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import lombok.AllArgsConstructor;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@EnableCaching
@AllArgsConstructor
public class CaffeineConfig {

    private final AppProperties appProperties;

    @Bean
    public Cache<String, UserConnectionInfo> userConnectionsCache() {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getUserConnections().getMaximumSize())
                .expireAfterWrite(appProperties.getCache().getUserConnections().getExpireAfterWrite())
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, List<UserMessageInfo>> userMessagesCache() {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getUserMessages().getMaximumSize())
                .expireAfterWrite(appProperties.getCache().getUserMessages().getExpireAfterWrite())
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, List<PendingEventInfo>> pendingEventsCache() {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getPendingEvents().getMaximumSize())
                .expireAfterWrite(appProperties.getCache().getPendingEvents().getExpireAfterWrite())
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, UserSessionInfo> userSessionCache() {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getUserSession().getMaximumSize())
                .expireAfterAccess(appProperties.getCache().getUserSession().getExpireAfterAccess())
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, BroadcastStatsInfo> broadcastStatsCache() {
        return Caffeine.newBuilder()
                .maximumSize(appProperties.getCache().getBroadcastStats().getMaximumSize())
                .expireAfterWrite(appProperties.getCache().getBroadcastStats().getExpireAfterWrite())
                .recordStats()
                .build();
    }

}