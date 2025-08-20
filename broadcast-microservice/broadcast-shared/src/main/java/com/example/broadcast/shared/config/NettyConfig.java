package com.example.broadcast.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import lombok.RequiredArgsConstructor;

import org.springframework.boot.web.embedded.netty.NettyReactiveWebServerFactory;
import reactor.netty.resources.LoopResources;

@Configuration
@RequiredArgsConstructor
public class NettyConfig {

    private final AppProperties appProperties;
    
    @Bean
    public NettyReactiveWebServerFactory nettyReactiveWebServerFactory() {
        NettyReactiveWebServerFactory factory = new NettyReactiveWebServerFactory();
        
        String serviceName = appProperties.getService().getName();
        String threadPrefix = serviceName; //+ "-nio-"
        
        factory.addServerCustomizers(server -> 
            server.runOn(LoopResources.create(threadPrefix, 1, true))
        );
        return factory;
    }
}