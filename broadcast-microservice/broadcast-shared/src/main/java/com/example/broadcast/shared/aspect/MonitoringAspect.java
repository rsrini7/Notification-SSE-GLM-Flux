package com.example.broadcast.shared.aspect;

import com.example.broadcast.shared.config.MonitoringConfig;
import com.example.broadcast.shared.dto.CorrelatedRequest;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import java.lang.reflect.Method;

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

    // A single, generic pointcut for all monitored methods and classes
    @Around("@within(com.example.broadcast.shared.aspect.Monitored) || @annotation(com.example.broadcast.shared.aspect.Monitored)")
    public Object monitorMethod(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        
         // 1. Check for annotation on the specific method first (for overrides).
        Monitored monitoredAnnotation = method.getAnnotation(Monitored.class);
        
        // 2. If not on the method, check on the original declaring class/interface.
        // This is more robust than checking the proxy's class.
        if (monitoredAnnotation == null) {
            monitoredAnnotation = method.getDeclaringClass().getAnnotation(Monitored.class);
        }
 
        // This check is a safeguard in case the pointcut matches but we still can't find it.
        if (monitoredAnnotation == null) {
            return joinPoint.proceed();
        }
        
        String operationType = monitoredAnnotation.value();
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = signature.getName();
        long startTime = System.currentTimeMillis();

        // Add correlation ID to the trace span if applicable
        Span currentSpan = Span.current();
        if (currentSpan.getSpanContext().isValid()) {
            for (Object arg : joinPoint.getArgs()) {
                if (arg instanceof CorrelatedRequest request && request.getCorrelationId() != null) {
                    currentSpan.setAttribute("app.correlation_id", request.getCorrelationId());
                    break;
                }
            }
        }

        try {
            Object result = joinPoint.proceed();
            long duration = System.currentTimeMillis() - startTime;
            
            metricsCollector.recordTimer("broadcast." + operationType + ".latency", duration, "class", className, "method", methodName, "status", "success");
            metricsCollector.incrementCounter("broadcast." + operationType + ".calls", "class", className, "method", methodName, "status", "success");
            
            log.debug("{}.{} ({}) completed successfully in {}ms", className, methodName, operationType, duration);
            return result;
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;

            metricsCollector.recordTimer("broadcast." + operationType + ".latency", duration, "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast." + operationType + ".calls", "class", className, "method", methodName, "status", "error");
            metricsCollector.incrementCounter("broadcast.errors", "type", operationType, "class", className, "method", methodName);
            
            if (currentSpan.getSpanContext().isValid()) {
                currentSpan.recordException(e);
            }

            log.error("{}.{} ({}) failed after {}ms: {}", className, methodName, operationType, duration, e.getMessage());
            throw e;
        }
    }
}