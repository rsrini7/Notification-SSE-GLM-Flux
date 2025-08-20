package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;

public record GeodeSsePayload(String targetPodId, MessageDeliveryEvent event) {}