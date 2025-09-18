package com.example.broadcast.shared.dto;

import java.io.Serializable;
import java.util.Map;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;


@Getter
@ToString
@RequiredArgsConstructor
public class GeodeSsePayload implements Serializable {
    private final String targetClusterPodName;
    private final MessageDeliveryEvent event;
    private final Map<String, String> traceContext;
}