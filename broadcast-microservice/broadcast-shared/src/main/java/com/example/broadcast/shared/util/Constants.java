package com.example.broadcast.shared.util;

public final class Constants {

    // Private constructor to prevent instantiation
    private Constants() {}

    public static final String DLT_SUFFIX = "-dlt";

    public static final class GeodeRegionNames {
        private GeodeRegionNames() {}
        public static final String USER_CONNECTIONS = "user-connections";
        public static final String CONNECTION_HEARTBEAT = "connection-heartbeat";
        public static final String BROADCAST_CONTENT = "broadcast-content";
        public static final String SSE_USER_MESSAGES = "sse-user-messages";
        public static final String SSE_ALL_MESSAGES = "sse-all-messages";
    }

    public enum BroadcastStatus {
        PREPARING,
        READY,
        ACTIVE,
        SCHEDULED,
        EXPIRED,
        CANCELLED,
        FAILED
    }

    public enum DeliveryStatus {
        PENDING,
        DELIVERED,
        FAILED,
        SUPERSEDED
    }

    public enum ReadStatus {
        UNREAD,
        READ
    }

    public enum TargetType {
        ALL,
        SELECTED,
        ROLE,
        PRODUCT
    }

    public enum EventType {
        CREATED,
        READ,
        CANCELLED,
        EXPIRED,
        FAILED
    }

    public enum SseEventType {
        CONNECTED,
        MESSAGE,
        READ_RECEIPT,
        MESSAGE_REMOVED,
        HEARTBEAT,
        SERVER_SHUTDOWN
    }
}