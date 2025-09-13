package com.example.broadcast.shared.aspect;

import com.example.broadcast.shared.config.MonitoringConfig;
import com.example.broadcast.shared.dto.CorrelatedRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class MonitoringAspect {

    private final MonitoringConfig.BroadcastMetricsCollector metricsCollector;

    @Bean
    public OpenTelemetry openTelemetry() {
       return GlobalOpenTelemetry.get();
    }

    @Around("execution(* com.example.broadcast.admin.service.BroadcastLifecycleService.*(..))")
    public Object monitorBroadcastAdminService(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.service.latency", duration, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.service.calls", "method", methodName, "status", "success");
            log.debug("BroadcastAdminService.{} completed successfully in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.service.latency", duration, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.service.calls", "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "service", "method", methodName);
            log.error("BroadcastAdminService.{} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.user.service.SseService.*(..))")
    public Object monitorSseService(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.sse.latency", duration, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.sse.calls", "method", methodName, "status", "success");
            log.debug("SseService.{} completed successfully in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.sse.latency", duration, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.sse.calls", "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "sse", "method", methodName);
            log.error("SseService.{} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.user.service.KafkaConsumerService.*(..))")
    public Object monitorKafkaConsumerService(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.kafka.consumer.latency", duration, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.kafka.consumer.calls", "method", methodName, "status", "success");
            log.debug("KafkaConsumerService.{} completed successfully in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.kafka.consumer.latency", duration, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.kafka.consumer.calls", "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "kafka", "method", methodName);
            log.error("KafkaConsumerService.{} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.shared.repository.*Repository.*(..))")
    public Object monitorRepository(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.database.latency", duration, "class", className, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.database.calls", "class", className, "method", methodName, "status", "success");
            
            if ("OutboxRepository".equals(className) && "findAndLockUnprocessedEvents".equals(methodName)) {
                log.trace("{}.{} completed successfully in {}ms", className, methodName, duration);
            } else {
                log.debug("{}.{} completed successfully in {}ms", className, methodName, duration);
            }

            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.database.latency", duration, "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.database.calls", "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "database", "class", className, "method", methodName);
            log.error("{}.{} failed after {}ms: {}", className, methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.*.controller.*Controller.*(..))")
    public Object monitorController(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        // Get the currently active span (created automatically by the OTel Agent)
        Span currentSpan = Span.current();
        
        // Find and add the correlation_id from request DTOs to the span
        if (currentSpan.getSpanContext().isValid()) {
            for (Object arg : joinPoint.getArgs()) {
                if (arg instanceof CorrelatedRequest request) {
                    if (request.getCorrelationId() != null) {
                        currentSpan.setAttribute("app.correlation_id", request.getCorrelationId());
                        // Once found, we can break the loop
                        break;
                    }
                }
                // Add other 'if instanceof' checks here for other DTOs if needed
            }
        }

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.controller.latency", duration, "class", className, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.controller.calls", "class", className, "method", methodName, "status", "success");
            log.debug("{}.{} completed successfully in {}ms", className, methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.controller.latency", duration, "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.controller.calls", "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "controller", "class", className, "method", methodName);
            
            // Add error details to the span
            if (currentSpan.getSpanContext().isValid()) {
                currentSpan.recordException(e);
            }

            log.error("{}.{} failed after {}ms: {}", className, methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.shared.service.cache.CacheService.*(..))")
    public Object monitorCacheService(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.cache.latency", duration, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.cache.calls", "method", methodName, "status", "success");
            log.debug("CacheService.{} completed successfully in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.cache.latency", duration, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.cache.calls", "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "cache", "method", methodName);
            log.error("CacheService.{} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }



}