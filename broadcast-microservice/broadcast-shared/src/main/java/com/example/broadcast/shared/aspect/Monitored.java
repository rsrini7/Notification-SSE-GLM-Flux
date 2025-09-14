package com.example.broadcast.shared.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {
    /**
     * A descriptive name for the type of operation being monitored (e.g., "service", "controller").
     * This will be used as a metric tag.
     */
    String value();
}