package com.example.broadcast.aspect;

import com.example.broadcast.config.MonitoringConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class MonitoringAspect {

    private final MonitoringConfig.BroadcastMetricsCollector metricsCollector;

    @Around("execution(* com.example.broadcast.service.BroadcastService.*(..))")
    public Object monitorBroadcastService(ProceedingJoinPoint joinPoint) throws Throwable {
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.service.latency", duration, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.service.calls", "method", methodName, "status", "success");
            log.debug("BroadcastService.{} completed successfully in {}ms", methodName, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.service.latency", duration, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.service.calls", "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", "service", "method", methodName);
            log.error("BroadcastService.{} failed after {}ms: {}", methodName, duration, e.getMessage());
            throw e;
        }
    }

    @Around("execution(* com.example.broadcast.service.SseService.*(..))")
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

    @Around("execution(* com.example.broadcast.service.KafkaConsumerService.*(..))")
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

    @Around("execution(* com.example.broadcast.repository.*Repository.*(..))")
    public Object monitorRepository(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            metricsCollector.recordTimer("broadcast.database.latency", duration, "class", className, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast.database.calls", "class", className, "method", methodName, "status", "success");
            log.debug("{}.{} completed successfully in {}ms", className, methodName, duration);
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

    @Around("execution(* com.example.broadcast.controller.*Controller.*(..))")
    public Object monitorController(ProceedingJoinPoint joinPoint) throws Throwable {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        long startTime = System.currentTimeMillis();
        
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
            log.error("{}.{} failed after {}ms: {}", className, methodName, duration, e.getMessage());
            throw e;
        }
    }

    /**
     * Monitor cache service methods
     */
    // MODIFIED: Pointcut now targets the 'CacheService' interface, making it work for any implementation.
    @Around("execution(* com.example.broadcast.service.CacheService.*(..))")
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

    /**
     * Log method parameters for debugging (only at DEBUG level)
     */
    // private void logMethodParameters(ProceedingJoinPoint joinPoint) {
    //     if (log.isDebugEnabled()) {
    //         String methodName = joinPoint.getSignature().getName();
    //         Object[] args = joinPoint.getArgs();
            
    //         if (args.length > 0) {
    //             log.debug("{}.{} called with parameters: {}", 
    //                     joinPoint.getTarget().getClass().getSimpleName(),
    //                     methodName,
    //                     Arrays.toString(args));
    //         }
    //     }
    // }
}