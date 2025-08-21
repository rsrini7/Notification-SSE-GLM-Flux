package com.example.broadcast.user.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.client.ClientCache;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class ReadyForEventsListener implements ApplicationListener<ContextRefreshedEvent> {

    private final ClientCache clientCache;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // This event fires after the Spring context is fully initialized,
        // ensuring our @PostConstruct in SseMessageCqListener has run.
        if (!clientCache.isClosed()) {
            log.info("Spring context refreshed. Signaling to Geode server that client is ready for events.");
            clientCache.readyForEvents();
            log.info("Client is now ready for events. Durable message queue processing will begin.");
        }
    }
}