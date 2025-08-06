package com.example.broadcast.admin.service;

import org.springframework.stereotype.Service;

/**
 * A simple, in-memory service to control testing-related flags.
 * This allows enabling/disabling test behaviors without restarting the application.
 */
@Service
public class TestingConfigurationService {

    private volatile boolean kafkaConsumerFailureEnabled = false;

    public boolean isKafkaConsumerFailureEnabled() {
        return kafkaConsumerFailureEnabled;
    }

    public void setKafkaConsumerFailureEnabled(boolean kafkaConsumerFailureEnabled) {
        this.kafkaConsumerFailureEnabled = kafkaConsumerFailureEnabled;
    }
}