package com.example.broadcast.shared.config;

import org.crac.Context;
import org.crac.Core;
import org.crac.Resource;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ReactorResourceFactory;

/**
 * This is the definitive CRaC hook for a Spring Boot WebFlux (Netty) application.
 * It correctly manages the lifecycle of both the high-level WebServer and the
 * low-level ReactorResourceFactory, which holds the native file descriptors (eventfd, eventpoll)
 * that must be closed before a checkpoint can be taken.
 */
@Configuration
public class CracNettyShutdownConfig implements Resource {

    private final WebServerApplicationContext context;
    private final ReactorResourceFactory resourceFactory;

    public CracNettyShutdownConfig(WebServerApplicationContext context, ReactorResourceFactory resourceFactory) {
        this.context = context;
        this.resourceFactory = resourceFactory;
        // Register this bean with the global CRaC context so its hooks are called.
        Core.getGlobalContext().register(this);
    }

    @Override
    public void beforeCheckpoint(Context<? extends Resource> context) throws Exception {
        System.out.println("CRAC HOOK: Stopping Netty server before checkpoint...");
        this.context.getWebServer().stop();

        // This is the key addition: explicitly destroy the underlying Netty resources.
        System.out.println("CRAC HOOK: Disposing Netty ReactorResourceFactory...");
        this.resourceFactory.destroy();
        System.out.println("CRAC HOOK: All Netty resources disposed.");
    }

    @Override
    public void afterRestore(Context<? extends Resource> context) throws Exception {
        // After restoring from the snapshot, we must re-initialize the resources
        // and then start the web server again.
        System.out.println("CRAC HOOK: Re-initializing Netty ReactorResourceFactory after restore...");
        this.resourceFactory.afterPropertiesSet();

        System.out.println("CRAC HOOK: Starting Netty server after restore...");
        this.context.getWebServer().start();
        System.out.println("CRAC HOOK: Netty server started.");
    }
}