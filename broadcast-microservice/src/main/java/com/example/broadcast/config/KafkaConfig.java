package com.example.broadcast.config;

import com.example.broadcast.dto.MessageDeliveryEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka configuration for broadcast messaging system
 * Configures producers, consumers, and topics for high-scale event streaming
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${broadcast.kafka.topic.name:broadcast-events}")
    private String topicName;

    @Value("${broadcast.kafka.topic.partitions:10}")
    private int topicPartitions;

    @Value("${broadcast.kafka.topic.replication-factor:3}")
    private short topicReplicationFactor;

    /**
     * Configure Kafka producer for sending broadcast events
     */
    @Bean
    public ProducerFactory<String, Object> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Basic producer configuration
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Reliability and durability settings
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        configProps.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        configProps.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        
        // Performance tuning
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 5);
        configProps.put(ProducerConfig.BUFFER_MEMORY_CONFIG, 33554432);
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        
        // High availability
        configProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 120000);
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Configure Kafka template for sending messages
     */
    @Bean
    public KafkaTemplate<String, Object> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }

    /**
     * Configure Kafka consumer for receiving broadcast events
     */
    @Bean
    public ConsumerFactory<String, MessageDeliveryEvent> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Basic consumer configuration
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        
        // Group and offset management
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "broadcast-service-group");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // Performance tuning
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        configProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 1000);
        configProps.put(ConsumerConfig.FETCH_MIN_BYTES_CONFIG, 1);
        configProps.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 1048576);
        
        // Session and heartbeat settings
        configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);
        configProps.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 10000);
        
        // Security and reliability
        configProps.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        
        // JSON deserializer configuration
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");
        configProps.put(JsonDeserializer.VALUE_DEFAULT_TYPE, MessageDeliveryEvent.class.getName());
        
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    /**
     * Configure Kafka listener container factory
     */
    @Bean
    public KafkaListenerContainerFactory<ConcurrentMessageListenerContainer<String, MessageDeliveryEvent>> 
            kafkaListenerContainerFactory() {
        
        ConcurrentKafkaListenerContainerFactory<String, MessageDeliveryEvent> factory = 
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3); // Number of consumer threads
        factory.getContainerProperties().setPollTimeout(3000);
        factory.getContainerProperties().setAckMode(org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        
        // Enable batch processing for better performance
        factory.setBatchListener(false);
        
        // Configure backpressure handling
        factory.getContainerProperties().setIdleBetweenPolls(100);
        
        return factory;
    }

    /**
     * Configure Kafka topic for broadcast events
     */
    @Bean
    public NewTopic broadcastTopic() {
        return TopicBuilder.name(topicName)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "604800000") // 7 days retention
                .config("cleanup.policy", "delete")
                .config("compression.type", "lz4")
                .config("max.message.bytes", "10485760") // 10MB max message size
                .config("message.timestamp.type", "CreateTime")
                .config("message.timestamp.difference.max.ms", "300000") // 5 minutes
                .build();
    }

    /**
     * Configure additional topics for different event types
     */
    @Bean
    public NewTopic deliveryEventsTopic() {
        return TopicBuilder.name(topicName + "-delivery")
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "86400000") // 24 hours retention
                .build();
    }

    @Bean
    public NewTopic readReceiptsTopic() {
        return TopicBuilder.name(topicName + "-read-receipts")
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "604800000") // 7 days retention
                .build();
    }

    /**
     * Configure admin client for topic management
     */
    @Bean
    public KafkaAdmin kafkaAdmin() {
        Map<String, Object> configs = new HashMap<>();
        configs.put(org.apache.kafka.clients.admin.AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        return new KafkaAdmin(configs);
    }
}