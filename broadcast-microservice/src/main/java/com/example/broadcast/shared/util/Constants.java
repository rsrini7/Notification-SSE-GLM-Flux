package com.example.broadcast.shared.util;

public final class Constants {

    // Private constructor to prevent instantiation
    private Constants() {}

    public static final String DLT_SUFFIX = ".DLT";

    public enum BroadcastStatus {
        ACTIVE,
        SCHEDULED,
        EXPIRED,
        CANCELLED
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