package com.example.broadcast.shared.dto;

import java.io.Serializable;

public record GeodeSsePayload(String targetPodId, MessageDeliveryEvent event) implements Serializable {}