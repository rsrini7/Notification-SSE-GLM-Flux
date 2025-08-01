package com.example.broadcast;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Main application class for the Broadcast Messaging Microservice
 * 
 * This microservice provides high-scale broadcast messaging capabilities with:
 * - Real-time SSE delivery for online users
 * - Persistent message storage in h2 Database
 * - Kafka-based event streaming for fan-out
 * - Caffeine caching for low-latency operations
 * - Support for 400K+ users with 30K+ concurrent connections
 */
@SpringBootApplication
@EnableKafka
@EnableAsync
@EnableScheduling
public class BroadcastMicroserviceApplication {

    public static void main(String[] args) {
        SpringApplication.run(BroadcastMicroserviceApplication.class, args);
    }
}