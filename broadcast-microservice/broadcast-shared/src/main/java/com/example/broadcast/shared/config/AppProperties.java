package com.example.broadcast.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Value;

@Data
@Validated
public class AppProperties {

    private String clusterName;

    private final Sse sse = new Sse();
    private final Db db = new Db();
    private final Kafka kafka = new Kafka();
    private final Pod pod = new Pod();
    private final Simulation simulation = new Simulation();
    private final H2Console h2Console = new H2Console();
    private final Service service = new Service();
    private final Geode geode = new Geode();

    @Data
    public static class Service{
        private String name = "";
    }

    @Data
    public static class H2Console{
        private String webPort = "8084";
        private String tcpPort = "9094";
    }

    @Data
    public static class Geode{
        private final Locator locator = new Locator();
        @Data
        public static class Locator{
            private String host = "localhost";
            private int port = 10334;
        }

    }

    @Data
    public static class Simulation {
        private long userFetchDelayMs = 0; // Default to 0 if not specified
    }

    @Data
    public static class Pod {
        @Value("${pod.name:${POD_NAME:broadcast-user-service-0}}")
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