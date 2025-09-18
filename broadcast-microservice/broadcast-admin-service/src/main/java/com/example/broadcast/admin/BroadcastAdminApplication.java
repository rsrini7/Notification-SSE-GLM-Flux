package com.example.broadcast.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import reactor.core.publisher.Hooks;

import org.springframework.context.annotation.ComponentScan;


/**
 * Main application class for the Broadcast Messaging Microservice
 * 
 * This microservice provides high-scale broadcast messaging capabilities with:
 * - Real-time SSE delivery for online users
 * - Persistent message storage in h2 Database
 * - Kafka-based event streaming for fan-out
 * - Geode caching for low-latency operations
 * - Support for 400K+ users with 30K+ concurrent connections
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
@EnableScheduling
@ComponentScan("com.example.broadcast")
public class BroadcastAdminApplication {


    static {
        // Manually and explicitly enable Reactor's automatic context propagation.
        // This ensures the thread-local MDC context is transferred across
        // different thread pools used by Reactor's schedulers.
        Hooks.enableAutomaticContextPropagation();
    }

    public static void main(String[] args) {
        SpringApplication.run(BroadcastAdminApplication.class, args);
    }
}