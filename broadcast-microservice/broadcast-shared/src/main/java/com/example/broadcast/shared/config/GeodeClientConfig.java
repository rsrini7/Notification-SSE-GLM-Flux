package com.example.broadcast.shared.config;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.PersistentUserMessageInfo;
import com.example.broadcast.shared.model.BroadcastMessage;

import lombok.RequiredArgsConstructor;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
public class GeodeClientConfig {

    private final AppProperties appProperties;

    @Bean(destroyMethod = "close")
    public ClientCache geodeClientCache() {

        String durableClientId = appProperties.getClusterName() + "_" + appProperties.getPod().getId();

        return new ClientCacheFactory()
                .addPoolLocator(appProperties.getGeode().getLocator().getHost(), appProperties.getGeode().getLocator().getPort())
                .setPoolSubscriptionEnabled(true)
                .set("durable-client-id", durableClientId)
                .set("durable-client-timeout", "300") // Timeout in seconds
                .set("log-level", "config")
                .create();
    }

    // Region for: userId -> serialized UserConnectionInfo JSON String
    @Bean("userConnectionsRegion")
    public Region<String, String> userConnectionsRegion(ClientCache clientCache) {
        return clientCache.<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("user-connections");
    }

    // Region for reverse lookup: connectionId -> userId
    @Bean("connectionToUserRegion")
    public Region<String, String> connectionToUserRegion(ClientCache clientCache) {
        return clientCache.<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("connection-to-user");
    }

    // Used like a distributed Set for checking if a user is online
    @Bean("onlineUsersRegion")
    public Region<String, Boolean> onlineUsersRegion(ClientCache clientCache) {
        return clientCache.<String, Boolean>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("online-users");
    }
    
    // Used like a distributed Set for tracking connections per pod
    @Bean("podConnectionsRegion")
    public Region<String, Set<String>> podConnectionsRegion(ClientCache clientCache) {
        return clientCache.<String, Set<String>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("pod-connections");
    }

    // Used like a Redis Sorted Set: connectionId -> heartbeat timestamp
    @Bean("heartbeatRegion")
    public Region<String, Long> heartbeatRegion(ClientCache clientCache) {
        return clientCache.<String, Long>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("heartbeat");
    }

    @Bean("pendingEventsRegion")
    public Region<String, List<MessageDeliveryEvent>> pendingEventsRegion(ClientCache clientCache) {
        return clientCache.<String, List<MessageDeliveryEvent>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("pending-events");
    }

    @Bean("broadcastContentRegion")
    public Region<Long, BroadcastMessage> broadcastContentRegion(ClientCache clientCache) {
        return clientCache.<Long, BroadcastMessage>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("broadcast-content");
    }

    @Bean("activeGroupBroadcastsRegion")
    public Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion(ClientCache clientCache) {
        return clientCache.<String, List<BroadcastMessage>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("active-group-broadcasts");
    }

    @Bean("userMessagesRegion")
    public Region<String, List<PersistentUserMessageInfo>> userMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, List<PersistentUserMessageInfo>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("user-messages");
    }

    @Bean("sseMessagesRegion")
    public Region<String, Object> sseMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("sse-messages");
    }

    // ADDED: Region to store the single "armed" flag for the DLT test
    @Bean("dltArmedRegion")
    public Region<String, Boolean> dltArmedRegion(ClientCache clientCache) {
        return clientCache.<String, Boolean>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("dlt-armed");
    }

    // ADDED: Region to act as a "Set" of broadcast IDs marked for failure
    @Bean("dltFailureIdsRegion")
    public Region<Long, Boolean> dltFailureIdsRegion(ClientCache clientCache) {
        return clientCache.<Long, Boolean>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create("dlt-failure-ids");
    }
}