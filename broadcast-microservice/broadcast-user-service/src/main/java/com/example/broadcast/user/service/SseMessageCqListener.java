package com.example.broadcast.user.service;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.user.constants.CacheConstants.GeodeRegionNames;

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

    private CqQuery userMessagesCq;
    private CqQuery groupMessagesCq;

    @PostConstruct
    public void registerCqs() {
        try {
            QueryService queryService = clientCache.getQueryService();
            CqAttributesFactory cqf = new CqAttributesFactory();
            cqf.addCqListener(this);
            CqAttributes cqa = cqf.create();

            String podName = appProperties.getPodName();
            String clusterName = appProperties.getClusterName();
            String uniqueClusterPodName = clusterName + ":" + podName;

            // CQ for User-Specific Messages: Filters by target pod name
            String userQuery = String.format("SELECT * FROM /%s s WHERE s.targetClusterPodName = '%s'",
                GeodeRegionNames.SSE_USER_MESSAGES,
                uniqueClusterPodName);
            this.userMessagesCq = queryService.newCq("UserMessagesCQ_" + uniqueClusterPodName.replace(":", "_"), userQuery, cqa, true);
            this.userMessagesCq.execute();
            log.info("Continuous Query registered for user-specific messages with query: {}", userQuery);

            // CQ for 'Group / Selected' Broadcast Messages: Gets everything from the region
            String groupQuery = String.format("SELECT * FROM /%s", GeodeRegionNames.SSE_GROUP_MESSAGES);
            this.groupMessagesCq = queryService.newCq("GroupMessagesCQ_" + uniqueClusterPodName.replace(":", "_"), groupQuery, cqa, true);
            this.groupMessagesCq.execute();
            log.info("Continuous Query registered for 'Group / Selected' broadcast messages with query: {}", groupQuery);

        } catch (CqException | RegionNotFoundException | CqExistsException e) {
            log.error("Failed to create Continuous Query", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    @Monitored("geode-cq-listener")
    public void onEvent(CqEvent cqEvent) {
        String messageKey = (String) cqEvent.getKey();
        CqQuery cq = cqEvent.getCq();

        if (!cqEvent.getQueryOperation().isCreate() && !cqEvent.getQueryOperation().isUpdate()) {
            return;
        }

        Object newValue = cqEvent.getNewValue();

        if (cq.getName().equals(this.groupMessagesCq.getName()) && newValue instanceof MessageDeliveryEvent event) {
            // Event came from the 'sse-group-messages' region
            log.info("Processing generic 'Group / Selected' broadcast event from CQ: {}", event);
            sseService.handleBroadcastToAllEvent(event);
            clientCache.getRegion(GeodeRegionNames.SSE_GROUP_MESSAGES).remove(messageKey);
            log.debug("Cleaned up 'Group / Selected' message with key: {}", messageKey);

        } else if (cq.getName().equals(this.userMessagesCq.getName()) && newValue instanceof GeodeSsePayload payload) {
            // Event came from the 'sse-user-messages' region
            log.info("Processing user-specific event from CQ: {}", payload.getEvent());
            sseService.handleMessageEvent(payload.getEvent());
            clientCache.getRegion(GeodeRegionNames.SSE_USER_MESSAGES).remove(messageKey);
            log.debug("Cleaned up user-specific message with key: {}", messageKey);
        } else {
            log.warn("Received unexpected payload type '{}' from CQ '{}'",
                (newValue != null) ? newValue.getClass().getName() : "null", cq.getName());
        }
    }

    @Override
    public void onError(CqEvent aCqEvent) {
        log.error("Error received on CQ '{}': {}", aCqEvent.getCq().getName(), aCqEvent.getThrowable().getMessage());
    }
}