// Location: src/main/java/com/example/broadcast/shared/config/AppProperties.java

package com.example.broadcast.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Configuration
@ConfigurationProperties(prefix = "broadcast")
@Data
@Validated
public class AppProperties {

    private final Sse sse = new Sse();
    private final Cache cache = new Cache();
    private final Db db = new Db();
    private final Kafka kafka = new Kafka();
    private final Pod pod = new Pod();

    @Data
    public static class Pod {
        @NotBlank
        private String id = "pod-local";
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
        private final UserSession userSession = new UserSession();
        private final BroadcastStats broadcastStats = new BroadcastStats();
        @Data
        public static class UserConnections {
            @Positive
            private int maximumSize = 50000;
            @NotNull
            private Duration expireAfterWrite = Duration.ofHours(1);
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

        @Data
        public static class UserSession {
            @Positive
            private int maximumSize = 10000;
            @NotNull
            private Duration expireAfterAccess = Duration.ofMinutes(30);
        }

        @Data
        public static class BroadcastStats {
            @Positive
            private int maximumSize = 1000;
            @NotNull
            private Duration expireAfterWrite = Duration.ofMinutes(5);
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
            private String nameAll = "broadcast-events-all";
            @NotBlank
            private String nameSelected = "broadcast-events-selected";
            // --- NEW TOPIC NAME ---
            @NotBlank
            private String nameCommands = "broadcast-commands";
            @Positive
            private int partitions = 10;
            @Positive
            private short replicationFactor = 1;
        }

        @Data
        public static class Consumer {
            @NotBlank
            private String dltGroupId = "broadcast-dlt-group";
            // --- NEW CONSUMER GROUP ID ---
            @NotBlank
            private String commandsGroupId = "broadcast-commands-group";
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