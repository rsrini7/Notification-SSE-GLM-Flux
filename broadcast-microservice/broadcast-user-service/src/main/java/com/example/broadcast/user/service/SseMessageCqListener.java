package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.GeodeSsePayload;

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
            String podId = appProperties.getPod().getId();
            String clusterName = appProperties.getClusterName();
            String uniquePodId = clusterName + ":" + podId;

            QueryService queryService = clientCache.getQueryService();
            CqAttributesFactory cqf = new CqAttributesFactory();
            cqf.addCqListener(this);
            CqAttributes cqa = cqf.create();

            String query = "SELECT * FROM /sse-messages s WHERE s.getTargetPodId = '" + uniquePodId + "'";
            CqQuery cq = queryService.newCq("SseMessageCQ_" + uniquePodId.replace(":", "_"), query, cqa);

            cq.execute();
            clientCache.readyForEvents();
            log.info("Continuous Query registered for pod '{}' with query: {}", uniquePodId, query);
        } catch (CqException | RegionNotFoundException | CqExistsException e) {
            log.error("Failed to create Continuous Query", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onEvent(CqEvent aCqEvent) {
        // This method is triggered when a 'put' in the orchestrator matches our query
        // CORRECTED: Use the isCreate() method on the Operation object.
        if (aCqEvent.getQueryOperation().isCreate()) {
            Object newValue = aCqEvent.getNewValue();
            if (newValue instanceof GeodeSsePayload payload) {
                log.info("CQ Event Received for pod {}: {}", appProperties.getPod().getId(), payload.getEvent());
                sseService.handleMessageEvent(payload.getEvent());
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