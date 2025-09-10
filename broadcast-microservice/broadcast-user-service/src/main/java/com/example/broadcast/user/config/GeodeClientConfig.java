package com.example.broadcast.user.config;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.BroadcastContent;
import com.example.broadcast.shared.dto.cache.ConnectionHeartbeat;
import com.example.broadcast.shared.dto.cache.UserConnectionInfo;
import com.example.broadcast.shared.dto.cache.UserMessageInbox;
import com.example.broadcast.shared.util.Constants.GeodeRegionNames;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class GeodeClientConfig {

    private final AppProperties appProperties;

    @Bean(destroyMethod = "close")
    public ClientCache geodeClientCache() {

        String durableClientId = appProperties.getClusterName() + "_" + appProperties.getPodName();

        log.info("Durable Client ID: {}",durableClientId);

        return new ClientCacheFactory()
                .addPoolLocator(appProperties.getGeode().getLocator().getHost(), appProperties.getGeode().getLocator().getPort())
                .setPoolSubscriptionEnabled(true)
                .setPoolSubscriptionRedundancy(1)
                .setPoolMinConnections(1)
                .set("durable-client-id", durableClientId)
                .set("durable-client-timeout", "30") // Timeout in seconds
                .set("log-level", "config")
                .create();
    }

    @Bean("userConnectionsRegion")
    public Region<String, UserConnectionInfo> userConnectionsRegion(ClientCache clientCache) {
        return clientCache.<String, UserConnectionInfo>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.USER_CONNECTIONS);
    }
   
    @Bean("connectionHeartbeatRegion")
    public Region<String, ConnectionHeartbeat> connectionHeartbeatRegion(ClientCache clientCache) {
        return clientCache.<String, ConnectionHeartbeat>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.CONNECTION_HEARTBEAT);
    }

    @Bean("userMessagesInboxRegion")
    public Region<String, List<UserMessageInbox>> userMessagesInboxRegion(ClientCache clientCache) {
        return clientCache.<String, List<UserMessageInbox>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("user-messages-inbox");
    }

    @Bean("broadcastContentRegion")
    public Region<Long, BroadcastContent> broadcastContentRegion(ClientCache clientCache) {
        return clientCache.<Long, BroadcastContent>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.BROADCAST_CONTENT);
    }

    @Bean("sseUserMessagesRegion")
    public Region<String, Object> sseUserMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.SSE_USER_MESSAGES);
    }

    @Bean("sseGroupMessagesRegion")
    public Region<String, Object> sseGroupMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.SSE_GROUP_MESSAGES);
    }

}