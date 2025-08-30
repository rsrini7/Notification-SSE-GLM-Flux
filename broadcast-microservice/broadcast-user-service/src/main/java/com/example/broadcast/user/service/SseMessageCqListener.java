package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.util.Constants.GeodeRegionNames;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.query.*;
import org.apache.geode.cache.util.CqListenerAdapter;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class SseMessageCqListener extends CqListenerAdapter {

    private final ClientCache clientCache;
    private final AppProperties appProperties;
    private final SseService sseService;

    @PostConstruct
    public void registerCq() {
        try {
            String podName = appProperties.getPodName();
            String clusterName = appProperties.getClusterName();
            String uniqueClusterPodName = clusterName + ":" + podName;

            QueryService queryService = clientCache.getQueryService();
            CqAttributesFactory cqf = new CqAttributesFactory();
            cqf.addCqListener(this);
            CqAttributes cqa = cqf.create();

            // REFACTORED QUERY: Listen for pod-specific events OR generic 'ALL_PODS' events.
            String query = String.format("SELECT * FROM /%s s WHERE s.targetClusterPodName = '%s' OR s.targetClusterPodName = '%s'",
                GeodeRegionNames.SSE_USER_MESSAGES,
                uniqueClusterPodName,
                GeodeRegionNames.SSE_ALL_MESSAGES
            );
            CqQuery cq = queryService.newCq("SseMessageCQ_" + uniqueClusterPodName.replace(":", "_"), query, cqa, true);

            cq.execute();
            log.info("Continuous Query registered for ClusterPod '{}' with query: {}", uniqueClusterPodName, query);
        } catch (CqException | RegionNotFoundException | CqExistsException e) {
            log.error("Failed to create Continuous Query", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEvent(CqEvent aCqEvent) {
        log.info("CQ Event received. Operation: {}", aCqEvent.getQueryOperation());
        
        String messageKey = (String) aCqEvent.getKey();

        if (aCqEvent.getQueryOperation().isCreate() || aCqEvent.getQueryOperation().isUpdate()) {
            Object newValue = aCqEvent.getNewValue();
            if (newValue instanceof GeodeSsePayload payload) {
                
                // REFACTORED LOGIC: Check if this is a generic broadcast or user-specific.
                if (GeodeRegionNames.SSE_ALL_MESSAGES.equals(payload.getTargetClusterPodName())) {
                    // This is a broadcast for ALL users.
                    log.info("Processing generic 'ALL' broadcast event on pod {}: {}", appProperties.getPodName(), payload.getEvent());
                    sseService.handleBroadcastToAllEvent(payload.getEvent());
                } else {
                    // This is an event for a specific user connected to this pod.
                    log.info("Processing user-specific event for on pod {}: {}", appProperties.getPodName(), payload.getEvent());
                    sseService.handleMessageEvent(payload.getEvent());
                }

                try {
                    clientCache.getRegion(GeodeRegionNames.SSE_USER_MESSAGES).remove(messageKey);
                    log.debug("Cleaned up processed message with key: {}", messageKey);
                } catch (Exception e) {
                    log.error("Failed to clean up processed sse-message with key: {}", messageKey, e);
                }

            } else {
                log.warn("Received unexpected payload type from CQ event: {}", (newValue != null) ? newValue.getClass().getName() : "null");
            }
        }
    }

    @Override
    public void onError(CqEvent aCqEvent) {
        log.error("Error received on CQ: {}", aCqEvent.getThrowable().getMessage());
    }
}