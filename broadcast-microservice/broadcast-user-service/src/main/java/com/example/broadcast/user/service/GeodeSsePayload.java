package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import java.io.Serializable;

public record GeodeSsePayload(String targetPodId, MessageDeliveryEvent event) implements Serializable {}