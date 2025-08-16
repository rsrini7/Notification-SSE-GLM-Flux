// NEW FILE
package com.example.broadcast.shared.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PropertiesConfig {

    // Inject the pod and cluster names directly from the environment/system properties
    @Value("${pod.name:${POD_NAME:pod-local-fallback}}")
    private String podName;

    @Value("${cluster.name:${CLUSTER_NAME:local}}")
    private String clusterName;

    @Bean
    @ConfigurationProperties(prefix = "broadcast")
    public AppProperties appProperties() {
        AppProperties properties = new AppProperties();
        
        // Manually and explicitly set the pod and cluster names
        properties.getPod().setId(podName);
        properties.setClusterName(clusterName);
        
        // The @ConfigurationProperties annotation will handle binding the rest of the
        // properties (like broadcast.sse.*, broadcast.kafka.*, etc.)
        return properties;
    }
}