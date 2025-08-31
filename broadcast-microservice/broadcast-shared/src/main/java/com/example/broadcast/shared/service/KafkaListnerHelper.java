package com.example.broadcast.shared.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class KafkaListnerHelper {

    private final AppProperties appProperties;

    private static final String ORCHESTRATOR_LISTNER_CONTAINER_FACTORY = "kafkaListenerContainerFactory";
    private static final String DLT_LISTNER_CONTAINER_FACTORY = "dltListenerContainerFactory";

    public String getOrchestrationTopic() {
        return appProperties.getKafka().getTopic().getNameOrchestration();
    }

    public String getOrchestrationDltTopic() {
        return getOrchestrationTopic() + Constants.DLT_SUFFIX;
    }

    public String getDltGroupId(){
        return appProperties.getKafka().getConsumer().getGroupDlt();
    }

    public String getOrchestrationGroupId(){
        return appProperties.getKafka().getConsumer().getGroupOrchestration();
    }

    public String getDltListnerContainerFactory(){
        return DLT_LISTNER_CONTAINER_FACTORY;
    }

    public String getOrchestratorListnerContainerFactory(){
        return ORCHESTRATOR_LISTNER_CONTAINER_FACTORY;
    }
}