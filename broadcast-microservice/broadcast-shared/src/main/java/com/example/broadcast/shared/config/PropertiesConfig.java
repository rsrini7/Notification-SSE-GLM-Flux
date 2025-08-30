// NEW FILE
package com.example.broadcast.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

@Configuration
@EnableJdbcRepositories(basePackages = "com.example.broadcast.shared.repository")
public class PropertiesConfig {

    @Value("${pod.name:${POD_NAME:broadcast-user-service-0}}")
    private String podName;

    @Value("${cluster.name:${CLUSTER_NAME:cluster-a}}")
    private String clusterName;

    @Bean
    @ConfigurationProperties(prefix = "broadcast")
    public AppProperties appProperties() {
        AppProperties properties = new AppProperties();
        
        // Manually and explicitly set the pod and cluster names
        properties.setPodName(podName);
        properties.setClusterName(clusterName);
        
        // The @ConfigurationProperties annotation will handle binding the rest of the
        // properties (like broadcast.sse.*, broadcast.kafka.*, etc.)
        return properties;
    }
}