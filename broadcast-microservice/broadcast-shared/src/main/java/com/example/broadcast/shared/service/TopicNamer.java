package com.example.broadcast.shared.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TopicNamer {

    private final AppProperties appProperties;

    public String getOrchestrationTopic() {
        return appProperties.getKafka().getTopic().getNameOrchestration();
    }

    public String getOrchestrationDltTopic() {
        return getOrchestrationTopic() + Constants.DLT_SUFFIX;
    }
}