package com.example.broadcast.config;

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
        private final String id="pod-local";
    }

    @Data
    public static class Sse {
        @Positive
        private final long timeout = 300000L; // FIXED: Corrected default value
        @Positive
        private final long heartbeatInterval = 30000L;
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
            private final int maximumSize = 50000;
            @NotNull
            private final Duration expireAfterWrite = Duration.ofHours(1);
        }

        @Data
        public static class UserMessages {
            @Positive
            private final int maximumSize = 100000;
            @NotNull
            private final Duration expireAfterWrite = Duration.ofHours(24);
        }

        @Data
        public static class PendingEvents {
            @Positive
            private final int maximumSize = 50000;
            @NotNull
            private final Duration expireAfterWrite = Duration.ofHours(6);
        }

        @Data
        public static class UserSession {
            @Positive
            private final int maximumSize = 10000;
            @NotNull
            private final Duration expireAfterAccess = Duration.ofMinutes(30);
        }

        @Data
        public static class BroadcastStats {
            @Positive
            private final int maximumSize = 1000;
            @NotNull
            private final Duration expireAfterWrite = Duration.ofMinutes(5);
        }
    }

    @Data
    public static class Db {
        @Positive
        private final int batchSize = 1000;
        @Positive
        private final int connectionPoolSize = 50;
    }

    @Data
    public static class Kafka {
        private  Topic topic = new Topic();
        private  Consumer consumer = new Consumer(); 
        private  Retry retry = new Retry();       

        @Data
        public static class Topic {
            @NotBlank
            private final String nameAll = "broadcast-events-all";
            @NotBlank
            private final String nameSelected = "broadcast-events-selected";
            @Positive
            private final int partitions = 10;
            @Positive
            private final short replicationFactor = 1;
        }

        @Data
        public static class Consumer {
            @NotBlank
            private final String dltGroupId = "broadcast-dlt-group";
        }

        @Data
        public static class Retry {
            @Positive
            private final int maxAttempts = 3;
            @Positive
            private final long backoffDelay = 1000L;
        }
    }
}