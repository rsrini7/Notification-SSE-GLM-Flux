package com.example.broadcast.shared.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.netty.resources.LoopResources;

@Configuration
@RequiredArgsConstructor
@Slf4j
public class NettyConfig {

    private final AppProperties appProperties;

    @Bean
    public WebServerFactoryCustomizer<NettyReactiveWebServerFactory> nettyWebServerCustomizer() {
        return factory -> {
            String threadPrefix = appProperties.getService().getName();

            // Create a new LoopResources that mimics the default behavior (using a properly sized
            // thread pool based on CPU cores) but with our custom thread prefix.
            LoopResources loopResources = LoopResources.create(
                threadPrefix,
                LoopResources.DEFAULT_IO_WORKER_COUNT, // This ensures a properly sized pool
                true
            );

            // Add a customizer to the factory to use our configured loop resources.
            factory.addServerCustomizers(server -> server.runOn(loopResources));

            log.info("Customized Netty server with thread prefix '{}' and default worker count.", threadPrefix);
        };
    }
}