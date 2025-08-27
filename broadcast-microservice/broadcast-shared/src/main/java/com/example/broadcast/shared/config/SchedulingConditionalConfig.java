package com.example.broadcast.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * This configuration enables Spring's scheduling capabilities for all profiles
 * EXCEPT the 'checkpoint-build' profile.
 */
@Configuration
@EnableScheduling
@Profile("!checkpoint-build")
public class SchedulingConditionalConfig {
}