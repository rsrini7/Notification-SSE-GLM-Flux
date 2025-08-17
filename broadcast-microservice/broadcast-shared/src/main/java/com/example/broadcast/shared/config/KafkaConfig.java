package com.example.broadcast.shared.config;

import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.RecordDeserializationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Configuration
@EnableKafka
@RequiredArgsConstructor
@Slf4j
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;
    private final AppProperties appProperties;

    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate(ProducerFactory<String, Object> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    @Bean
    public ConsumerFactory<String, Object> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
       
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // Configure the value deserializer
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.USE_TYPE_INFO_HEADERS, false);
        configProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, "com.example.broadcast.shared.dto.MessageDeliveryEvent");
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");

        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> kafkaListenerContainerFactory(
        ConsumerFactory<String, Object> consumerFactory,
        DefaultErrorHandler errorHandler) {
        
        ConcurrentKafkaListenerContainerFactory<String, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        FixedBackOff backOff = new FixedBackOff(1000L, 2L);
        DefaultErrorHandler errorHandler = new ConciseLoggingErrorHandler(deadLetterPublishingRecoverer, backOff);
        
        errorHandler.addNotRetryableExceptions(RecordDeserializationException.class);
        
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        errorHandler.setCommitRecovered(true);
        return errorHandler;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            @Qualifier("dltKafkaTemplate") KafkaTemplate<String, Object> dltKafkaTemplate) {

        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver = (cr, e) -> {
            String workerTopicPrefix = appProperties.getKafka().getTopic().getNameWorkerPrefix();
            String orchestrationTopic = appProperties.getKafka().getTopic().getNameOrchestration();

            // CORRECTED LOGIC: Check if the topic name CONTAINS the worker prefix,
            // which accounts for the cluster name at the beginning.
            if (cr.topic().contains(workerTopicPrefix)) {
                String sharedDltTopic = workerTopicPrefix + Constants.DLT_SUFFIX;
                log.info("Routing failed record from worker topic {} to shared DLT {}", cr.topic(), sharedDltTopic);
                return new TopicPartition(sharedDltTopic, 0);
            }

            if (cr.topic().equals(orchestrationTopic)) {
                String orchestrationDltTopic = orchestrationTopic + Constants.DLT_SUFFIX;
                log.info("Routing failed record from orchestration topic {} to its DLT {}", cr.topic(), orchestrationDltTopic);
                return new TopicPartition(orchestrationDltTopic, 0);
            }
            
            // This fallback should ideally not be hit.
            log.warn("Unrecognized topic '{}' in DLT resolver. Using default fallback DLT name.", cr.topic());
            return new TopicPartition(cr.topic() + Constants.DLT_SUFFIX, 0);
        };
        // ========================= END OF FIX =========================

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dltKafkaTemplate, destinationResolver);
        recoverer.setFailIfSendResultIsError(true);
        
        return recoverer;
    }
    
    @Bean
    public KafkaTemplate<String, Object> dltKafkaTemplate(
            @Qualifier("dltProducerFactory") ProducerFactory<String, Object> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    @Bean
    public ProducerFactory<String, Object> dltProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    @Bean
    public NewTopic orchestrationTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopic().getNameOrchestration())
                .partitions(1) // Low-volume topic, one partition is sufficient
                .replicas(appProperties.getKafka().getTopic().getReplicationFactor())
                .config("retention.ms", "604800000") // 7 days
                .build();
    }

    // NEW: Bean for the orchestration DLT
    @Bean
    public NewTopic orchestrationDeadLetterTopic() {
        return TopicBuilder.name(appProperties.getKafka().getTopic().getNameOrchestration() + Constants.DLT_SUFFIX)
            .partitions(1)
            .replicas(appProperties.getKafka().getTopic().getReplicationFactor())
            .config("retention.ms", "1209600000") // 14 days
            .build();
    }
    
    // NEW: Bean for the SHARED worker DLT
    @Bean
    public NewTopic workerEventsDeadLetterTopic() {
        // All pod-specific topics will use this single DLT.
        // The original topic is preserved in the DLT message headers.
        return TopicBuilder.name(appProperties.getKafka().getTopic().getNameWorkerPrefix() + Constants.DLT_SUFFIX)
            .partitions(1)
            .replicas(appProperties.getKafka().getTopic().getReplicationFactor())
            .config("retention.ms", "1209600000") // 14 days
            .build();
    }

    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }
}