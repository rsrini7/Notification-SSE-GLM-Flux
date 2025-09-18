package com.example.broadcast.user.service;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.dto.GeodeSsePayload;
import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.constants.CacheConstants.GeodeRegionNames;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.client.ClientCache;
import org.apache.geode.cache.query.*;
import org.apache.geode.cache.util.CqListenerAdapter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SseMessageCqListener extends CqListenerAdapter {

    private final ClientCache clientCache;
    private final AppProperties appProperties;
    private final SseService sseService;
    private final Tracer tracer;

    private CqQuery userMessagesCq;
    private CqQuery groupMessagesCq;

    public SseMessageCqListener(ClientCache clientCache, AppProperties appProperties, SseService sseService, OpenTelemetry openTelemetry) {
        this.clientCache = clientCache;
        this.appProperties = appProperties;
        this.sseService = sseService;
        this.tracer = openTelemetry.getTracer(SseMessageCqListener.class.getName(), "1.0.0");
    }

    // This TextMapGetter is used by OpenTelemetry to extract trace context from our GeodePayload
    private static final TextMapGetter<GeodeSsePayload> geodePayloadGetter = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(GeodeSsePayload carrier) {
            return carrier.getTraceContext().keySet();
        }

        @Nullable
        @Override
        public String get(@Nullable GeodeSsePayload carrier, String key) {
            return carrier == null ? null : carrier.getTraceContext().get(key);
        }
    };

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
        Object newValue = cqEvent.getNewValue();
        
        // 1. Extract the parent trace context from the incoming Geode event
        Context parentContext = Context.current();
        if (newValue instanceof GeodeSsePayload payload && payload.getTraceContext() != null) {
            parentContext = GlobalOpenTelemetry.getPropagators().getTextMapPropagator()
                .extract(parentContext, payload, geodePayloadGetter);
        }

        // 2. Start a new span as a child of the extracted context
        Span span = tracer.spanBuilder("Geode CQ Event")
            .setParent(parentContext)
            .setSpanKind(SpanKind.CONSUMER)
            .startSpan();

        // 3. Use try-with-resources for the span's scope and a finally block to end it
        try (var scope = span.makeCurrent()) {
            // Your existing MDC logic is nested inside the span's scope
            String correlationId = null;
            if (newValue instanceof MessageDeliveryEvent event) {
                correlationId = event.getCorrelationId();
            } else if (newValue instanceof GeodeSsePayload payload) {
                correlationId = payload.getEvent().getCorrelationId();
            }

            if (correlationId != null) {
                MDC.put(Constants.CORRELATION_ID, correlationId);
                span.setAttribute("app.correlation_id", correlationId); // Also add to span
            }

            try {
                // Your original business logic
                String messageKey = (String) cqEvent.getKey();
                CqQuery cq = cqEvent.getCq();

                if (!cqEvent.getQueryOperation().isCreate() && !cqEvent.getQueryOperation().isUpdate()) {
                    return;
                }

                if (cq.getName().equals(this.groupMessagesCq.getName()) && newValue instanceof MessageDeliveryEvent event) {
                    log.info("Processing generic 'Group / Selected' broadcast event from CQ: {}", event);
                    sseService.handleBroadcastToAllEvent(event);
                    clientCache.getRegion(GeodeRegionNames.SSE_GROUP_MESSAGES).remove(messageKey);
                    log.debug("Cleaned up 'Group / Selected' message with key: {}", messageKey);
                } else if (cq.getName().equals(this.userMessagesCq.getName()) && newValue instanceof GeodeSsePayload payload) {
                    log.info("Processing user-specific event from CQ: {}", payload.getEvent());
                    sseService.handleMessageEvent(payload.getEvent());
                    clientCache.getRegion(GeodeRegionNames.SSE_USER_MESSAGES).remove(messageKey);
                    log.debug("Cleaned up user-specific message with key: {}", messageKey);
                } else {
                    log.warn("Received unexpected payload type '{}' from CQ '{}'",
                            (newValue != null) ? newValue.getClass().getName() : "null", cq.getName());
                }
            } finally {
                // Always clear the MDC
                MDC.remove(Constants.CORRELATION_ID);
            }
        } catch (Exception e) {
            span.recordException(e); // Record exceptions on the span
            throw e;
        } finally {
            // Always end the span
            span.end();
        }
    }

    @Override
    public void onError(CqEvent aCqEvent) {
        log.error("Error received on CQ '{}': {}", aCqEvent.getCq().getName(), aCqEvent.getThrowable().getMessage());
    }
}