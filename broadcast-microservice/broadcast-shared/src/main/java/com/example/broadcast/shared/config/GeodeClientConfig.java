package com.example.broadcast.shared.config;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.ConnectionMetadata;
import com.example.broadcast.shared.dto.cache.PersistentUserMessageInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.util.Constants.GeodeRegionNames;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.geode.cache.Region;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.client.ClientCacheFactory;
import org.apache.geode.cache.client.ClientRegionShortcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;
import java.util.Set;

@Configuration
@RequiredArgsConstructor
@Slf4j
@Profile("!admin-only")
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

    // Region for: userId -> serialized UserConnectionInfo JSON String
    @Bean("userConnectionsRegion")
    public Region<String, String> userConnectionsRegion(ClientCache clientCache) {
        return clientCache.<String, String>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.USER_CONNECTIONS);
    }
   
    // Used like a distributed Set for tracking connections per pod
    @Bean("clusterPodConnectionsRegion")
    public Region<String, Set<String>> clusterPodConnectionsRegion(ClientCache clientCache) {
        return clientCache.<String, Set<String>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.CLUSTER_POD_CONNECTIONS);
    }

    @Bean("clusterPodHeartbeatsRegion")
    public Region<String, Long> clusterPodHeartbeatsRegion(ClientCache clientCache) {
        return clientCache.<String, Long>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.CLUSTER_POD_HEARTBEATS);
    }

    // NEW CONSOLIDATED REGION for connectionId -> {userId, heartbeatTimestamp}
    @Bean("connectionMetadataRegion")
    public Region<String, ConnectionMetadata> connectionMetadataRegion(ClientCache clientCache) {
        return clientCache.<String, ConnectionMetadata>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.CONNECTION_METADATA);
    }

    @Bean("pendingEventsRegion")
    public Region<String, List<MessageDeliveryEvent>> pendingEventsRegion(ClientCache clientCache) {
        return clientCache.<String, List<MessageDeliveryEvent>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.PENDING_EVENTS);
    }

    @Bean("broadcastContentRegion")
    public Region<Long, BroadcastMessage> broadcastContentRegion(ClientCache clientCache) {
        return clientCache.<Long, BroadcastMessage>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.BROADCAST_CONTENT);
    }

    @Bean("activeGroupBroadcastsRegion")
    public Region<String, List<BroadcastMessage>> activeGroupBroadcastsRegion(ClientCache clientCache) {
        return clientCache.<String, List<BroadcastMessage>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.ACTIVE_GROUP_BROADCASTS);
    }

    @Bean("userMessagesRegion")
    public Region<String, List<PersistentUserMessageInfo>> userMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, List<PersistentUserMessageInfo>>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.USER_MESSAGES);
    }

    @Bean("sseMessagesRegion")
    public Region<String, Object> sseMessagesRegion(ClientCache clientCache) {
        return clientCache.<String, Object>createClientRegionFactory(ClientRegionShortcut.PROXY)
                .create(GeodeRegionNames.SSE_MESSAGES);
    }

}