package com.example.broadcast.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
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
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.FixedBackOff;
import com.example.broadcast.util.Constants;

import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

@Configuration
@EnableKafka
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${broadcast.kafka.topic.name.all:broadcast-events-all}")
    private String allUsersTopicName;

    @Value("${broadcast.kafka.topic.name.selected:broadcast-events-selected}")
    private String selectedUsersTopicName;

    @Value("${broadcast.kafka.topic.partitions:10}")
    private int topicPartitions;

    @Value("${broadcast.kafka.topic.replication-factor:1}")
    private short topicReplicationFactor;

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
    public ConsumerFactory<String, byte[]> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "broadcast-service-group");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<>(configProps);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> kafkaListenerContainerFactory(DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }

    @Bean
    public DefaultErrorHandler errorHandler(DeadLetterPublishingRecoverer deadLetterPublishingRecoverer) {
        // After 2 retries (3 total attempts), the message will be sent to the DLT.
        FixedBackOff backOff = new FixedBackOff(1000L, 2L);
        // Use the new ConciseLoggingErrorHandler to reduce log noise
        DefaultErrorHandler errorHandler = new ConciseLoggingErrorHandler(deadLetterPublishingRecoverer, backOff);
        errorHandler.setLogLevel(KafkaException.Level.WARN);
        errorHandler.setCommitRecovered(true); // Commit the offset of the failed message
        return errorHandler;
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            @Qualifier("dltKafkaTemplate") KafkaTemplate<String, byte[]> dltKafkaTemplate) {
        
        BiFunction<ConsumerRecord<?, ?>, Exception, TopicPartition> destinationResolver = (cr, e) ->
                new TopicPartition(cr.topic() + Constants.DLT_SUFFIX, 0);
        
        // This is a standard recoverer. Its only job is to publish the failed record to the DLT.
        return new DeadLetterPublishingRecoverer(dltKafkaTemplate, destinationResolver);
    }

    @Bean
    public KafkaTemplate<String, byte[]> dltKafkaTemplate(
            @Qualifier("dltProducerFactory") ProducerFactory<String, byte[]> dltProducerFactory) {
        return new KafkaTemplate<>(dltProducerFactory);
    }

    @Bean
    public ProducerFactory<String, byte[]> dltProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(configProps);
    }
    
    @Bean
    public NewTopic allUsersBroadcastTopic() {
        return TopicBuilder.name(allUsersTopicName)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "604800000")
                .build();
    }

    @Bean
    public NewTopic selectedUsersBroadcastTopic() {
        return TopicBuilder.name(selectedUsersTopicName)
                .partitions(topicPartitions)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "604800000")
                .build();
    }

    @Bean
    public NewTopic allUsersDeadLetterTopic() {
        return TopicBuilder.name(allUsersTopicName + Constants.DLT_SUFFIX)
                .partitions(1)
                .replicas(topicReplicationFactor)
                .config("retention.ms", "1209600000") // 14 days
                .build();
    }

    @Bean
    public NewTopic selectedUsersDeadLetterTopic() {
        return TopicBuilder.name(selectedUsersTopicName + Constants.DLT_SUFFIX)
                .partitions(1)
                .replicas(topicReplicationFactor)
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