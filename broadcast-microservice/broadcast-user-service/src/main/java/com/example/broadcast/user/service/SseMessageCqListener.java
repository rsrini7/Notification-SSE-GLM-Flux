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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;


@Component
@Slf4j
@RequiredArgsConstructor
@Profile("!checkpoint-build") 
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

            String query = String.format("SELECT * FROM /%s s WHERE s.getTargetClusterPodName = '%s'", GeodeRegionNames.SSE_MESSAGES, uniqueClusterPodName);
            CqQuery cq = queryService.newCq("SseMessageCQ_" + uniqueClusterPodName.replace(":", "_"), query, cqa, true);

            cq.execute();
            log.info("Continuous Query registered for  ClusterPod '{}' with query: {}", uniqueClusterPodName, query);
        } catch (CqException | RegionNotFoundException | CqExistsException e) {
            log.error("Failed to create Continuous Query", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEvent(CqEvent aCqEvent) {
        log.info("CQ Event received. Operation: {}", aCqEvent.getQueryOperation());
        
        // Get the key of the message from the event
        String messageKey = (String) aCqEvent.getKey();

        if (aCqEvent.getQueryOperation().isCreate() || aCqEvent.getQueryOperation().isUpdate()) {
            Object newValue = aCqEvent.getNewValue();
            if (newValue instanceof GeodeSsePayload payload) {
                log.info("Processing CQ payload for cluster {} pod {}: {}", appProperties.getClusterName(), appProperties.getPodName(), payload.getEvent());
                sseService.handleMessageEvent(payload.getEvent());

                // After processing, remove the entry from the SSE_MESSAGES region to clean it up.
                try {
                    clientCache.getRegion(GeodeRegionNames.SSE_MESSAGES).remove(messageKey);
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