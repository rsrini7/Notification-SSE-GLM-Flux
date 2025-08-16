package com.example.broadcast.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "broadcast")
@Data
@Validated
public class AppProperties {

    @Value("${cluster.name:${CLUSTER_NAME:default}}")
    private String clusterName;

    private final Sse sse = new Sse();
    private final Cache cache = new Cache();
    private final Db db = new Db();
    private final Kafka kafka = new Kafka();
    private final Pod pod = new Pod();
    private final Simulation simulation = new Simulation();
    private final H2Console h2Console = new H2Console();

    @Data
    public static class H2Console{
        private String webPort = "8084";
        private String tcpPort = "9094";
    }

    @Data
    public static class Simulation {
        private long userFetchDelayMs = 0; // Default to 0 if not specified
    }

    @Data
    public static class Pod {
        @Value("${pod.name:${POD_NAME:pod-local}}")
        @NotBlank
        private String id;
    }

    @Data
    public static class Sse {
        @Positive
        private long timeout = 300000L;
        @Positive
        private long heartbeatInterval = 30000L;
    }

    @Data
    public static class Cache {
        private final UserConnections userConnections = new UserConnections();
        private final UserMessages userMessages = new UserMessages();
        private final PendingEvents pendingEvents = new PendingEvents();

        @Data
        public static class UserConnections {
            @Positive
            private int maximumSize = 50000;
            @NotNull
            private Duration expireAfterAccess = Duration.ofMinutes(30);
        }

        @Data
        public static class UserMessages {
            @Positive
            private int maximumSize = 100000;
            @NotNull
            private Duration expireAfterWrite = Duration.ofHours(24);
        }

        @Data
        public static class PendingEvents {
            @Positive
            private int maximumSize = 50000;
            @NotNull
            private Duration expireAfterWrite = Duration.ofHours(6);
        }
    }

    @Data
    public static class Db {
        @Positive
        private int batchSize = 1000;
        @Positive
        private int connectionPoolSize = 50;
    }

    @Data
    public static class Kafka {
        private final Topic topic = new Topic();
        private final Consumer consumer = new Consumer();
        private final Retry retry = new Retry();

        @Data
        public static class Topic {
            @NotBlank
            private String nameOrchestration = "broadcast-orchestration";
            @NotBlank
            private String nameWorkerPrefix = "broadcast-events-"; // Prefix for pod-specific topics
            @Positive
            private int partitions = 1;
            @Positive
            private short replicationFactor = 1;
        }

        @Data
        public static class Consumer {
            @NotBlank
            private String groupOrchestration = "broadcast-orchestration-group"; // Static group for the leader
            @NotBlank
            private String groupWorker = "broadcast-worker-group"; // Static group for workers
            @NotBlank
            private String groupDlt = "broadcast-dlt-group";
        }

        @Data
        public static class Retry {
            @Positive
            private int maxAttempts = 3;
            @Positive
            private long backoffDelay = 1000L;
        }
    }
}