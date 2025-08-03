package com.example.broadcast.config;

import com.example.broadcast.dto.cache.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableCaching
public class CaffeineConfig {

    @Value("${broadcast.cache.user-connections.maximum-size:50000}")
    private int userConnectionsMaxSize;
    @Value("${broadcast.cache.user-connections.expire-after-write:1h}")
    private String userConnectionsExpireAfterWrite;

    @Value("${broadcast.cache.user-messages.maximum-size:100000}")
    private int userMessagesMaxSize;
    @Value("${broadcast.cache.user-messages.expire-after-write:24h}")
    private String userMessagesExpireAfterWrite;

    @Value("${broadcast.cache.pending-events.maximum-size:50000}")
    private int pendingEventsMaxSize;
    @Value("${broadcast.cache.pending-events.expire-after-write:6h}")
    private String pendingEventsExpireAfterWrite;

    @Value("${broadcast.cache.user-session.maximum-size:10000}")
    private int userSessionMaxSize;
    @Value("${broadcast.cache.user-session.expire-after-access:30m}")
    private String userSessionExpireAfterAccess;

    @Value("${broadcast.cache.broadcast-stats.maximum-size:1000}")
    private int broadcastStatsMaxSize;
    @Value("${broadcast.cache.broadcast-stats.expire-after-write:5m}")
    private String broadcastStatsExpireAfterWrite;

    @Bean
    public Cache<String, UserConnectionInfo> userConnectionsCache() {
        return Caffeine.newBuilder()
                .maximumSize(userConnectionsMaxSize)
                .expireAfterWrite(parseDuration(userConnectionsExpireAfterWrite))
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, List<UserMessageInfo>> userMessagesCache() {
        return Caffeine.newBuilder()
                .maximumSize(userMessagesMaxSize)
                .expireAfterWrite(parseDuration(userMessagesExpireAfterWrite))
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, List<PendingEventInfo>> pendingEventsCache() {
        return Caffeine.newBuilder()
                .maximumSize(pendingEventsMaxSize)
                .expireAfterWrite(parseDuration(pendingEventsExpireAfterWrite))
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, UserSessionInfo> userSessionCache() {
        return Caffeine.newBuilder()
                .maximumSize(userSessionMaxSize)
                .expireAfterAccess(parseDuration(userSessionExpireAfterAccess))
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, BroadcastStatsInfo> broadcastStatsCache() {
        return Caffeine.newBuilder()
                .maximumSize(broadcastStatsMaxSize)
                .expireAfterWrite(parseDuration(broadcastStatsExpireAfterWrite))
                .recordStats()
                .build();
    }

    private Duration parseDuration(String durationStr) {
        try {
            if (durationStr.endsWith("h")) return Duration.ofHours(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            if (durationStr.endsWith("m")) return Duration.ofMinutes(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            if (durationStr.endsWith("s")) return Duration.ofSeconds(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
            return Duration.parse(durationStr);
        } catch (Exception e) {
            return Duration.ofMinutes(30);
        }
    }
}