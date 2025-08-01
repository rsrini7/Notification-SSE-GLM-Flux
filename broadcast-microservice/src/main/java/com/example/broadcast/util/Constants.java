package com.example.broadcast.util;

public final class Constants {

    // Private constructor to prevent instantiation
    private Constants() {}

    public enum BroadcastStatus {
        ACTIVE,
        SCHEDULED,
        EXPIRED,
        CANCELLED
    }

    public enum DeliveryStatus {
        PENDING,
        DELIVERED,
        FAILED
    }

    public enum ReadStatus {
        UNREAD,
        READ,
        ARCHIVED
    }

    public enum TargetType {
        ALL,
        SELECTED,
        ROLE
    }

    public enum EventType {
        CREATED,
        READ,
        CANCELLED,
        EXPIRED
    }

    public enum SseEventType {
        CONNECTED,
        MESSAGE,
        READ_RECEIPT,
        MESSAGE_REMOVED,
        HEARTBEAT
    }
}