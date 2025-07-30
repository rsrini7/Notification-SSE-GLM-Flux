package com.example.broadcast.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.server.WebFilter;
import reactor.core.publisher.Mono;

/**
 * Configuration for distributed tracing and logging
 * Uses Spring Cloud Sleuth for distributed tracing integration
 */
@Configuration
public class LoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(LoggingConfig.class);

    /**
     * Custom logging filter for WebFlux request/response logging
     */
    @Bean
    public WebFilter loggingFilter() {
        return (exchange, chain) -> {
            long startTime = System.currentTimeMillis();
            String path = exchange.getRequest().getURI().getPath();
            String method = exchange.getRequest().getMethod().name();

            // Log the incoming request
            if (log.isDebugEnabled()) {
                log.debug("Incoming request: {} {} from {} with headers: {}", 
                    method,
                    path,
                    String.valueOf(exchange.getRequest().getRemoteAddress()),
                    exchange.getRequest().getHeaders());
            }

            return chain.filter(exchange)
                .then(Mono.fromRunnable(() -> {
                    // Log the outgoing response
                    if (log.isDebugEnabled()) {
                        long duration = System.currentTimeMillis() - startTime;
                        log.debug("Outgoing response: {} {} - {} in {}ms", 
                            method,
                            path,
                            exchange.getResponse().getStatusCode(),
                            duration);
                    }
                }));
        };
    }

    /**
     * Structured logging configuration
     */
    @Bean
    public StructuredLoggingConfig structuredLoggingConfig() {
        return new StructuredLoggingConfig();
    }

    /**
     * Configuration for structured logging
     */
    public static class StructuredLoggingConfig {
        
        public void configureStructuredLogging() {
            // Configure logback for structured logging with trace IDs
            System.setProperty("logging.pattern.console", 
                    "{\"timestamp\":\"%d{yyyy-MM-dd HH:mm:ss.SSS}\",\"level\":\"%p\",\"logger\":\"%logger{36}\",\"message\":\"%m\",\"thread\":\"%thread\",\"traceId\":\"%X{traceId:-}\",\"spanId\":\"%X{spanId:-}\"}%n");
            
            System.setProperty("logging.pattern.file", 
                    "{\"timestamp\":\"%d{yyyy-MM-dd HH:mm:ss.SSS}\",\"level\":\"%p\",\"logger\":\"%logger{36}\",\"message\":\"%m\",\"thread\":\"%thread\",\"traceId\":\"%X{traceId:-}\",\"spanId\":\"%X{spanId:-}\"}%n");
        }
    }
}
