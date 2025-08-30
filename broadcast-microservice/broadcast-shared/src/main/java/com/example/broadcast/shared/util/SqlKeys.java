package com.example.broadcast.shared.util;

/**
 * Provides compile-time safe constants for all SQL query keys
 * defined in the sql/queries.properties file.
 */
public final class SqlKeys {

    private SqlKeys() {} // Private constructor to prevent instantiation

    // ===============================================
    // broadcast_messages queries
    // ===============================================
    public static final String BROADCAST_SAVE = "broadcast.save";
    public static final String BROADCAST_UPDATE = "broadcast.update";
    public static final String BROADCAST_FIND_BY_ID = "broadcast.findById";
    public static final String BROADCAST_FIND_ACTIVE_BY_ID = "broadcast.findActiveById";
    public static final String BROADCAST_FIND_WITH_STATS_BY_ID = "broadcast.findWithStatsById";
    public static final String BROADCAST_FIND_ACTIVE_WITH_STATS = "broadcast.findActiveWithStats";
    public static final String BROADCAST_FIND_ALL_WITH_STATS = "broadcast.findAllWithStats";
    public static final String BROADCAST_FIND_SCHEDULED_WITH_STATS = "broadcast.findScheduledWithStats";
    public static final String BROADCAST_FIND_EXPIRED = "broadcast.findExpired";
    public static final String BROADCAST_FIND_ACTIVE_BY_TARGET_TYPE = "broadcast.findActiveByTargetType";
    public static final String BROADCAST_UPDATE_STATUS = "broadcast.updateStatus";
    public static final String BROADCAST_FIND_AND_LOCK_READY = "broadcast.findAndLockReady";
    public static final String BROADCAST_FIND_FINALIZED_FOR_CLEANUP = "broadcast.findFinalizedForCleanup";
    public static final String BROADCAST_FIND_SCHEDULED_PRODUCT = "broadcast.findScheduledProductBroadcasts";
    public static final String BROADCAST_FIND_AND_LOCK_SCHEDULED_FAN_OUT = "broadcast.findAndLockScheduledFanOut";

    // ===============================================
    // user_broadcast_messages queries
    // ===============================================
    public static final String USER_BROADCAST_SAVE = "userBroadcast.save";
    public static final String USER_BROADCAST_FIND_BY_ID = "userBroadcast.findById";
    public static final String USER_BROADCAST_UPDATE = "userBroadcast.update";
    public static final String USER_BROADCAST_FIND_BY_USER_AND_BROADCAST_ID = "userBroadcast.findByUserIdAndBroadcastId";
    public static final String USER_BROADCAST_FIND_BY_BROADCAST_ID = "userBroadcast.findByBroadcastId";
    public static final String USER_BROADCAST_UPDATE_DELIVERY_STATUS = "userBroadcast.updateDeliveryStatus";
    public static final String USER_BROADCAST_MARK_AS_READ = "userBroadcast.markAsRead";
    public static final String USER_BROADCAST_BATCH_INSERT = "userBroadcast.batchInsert";
    public static final String USER_BROADCAST_UPDATE_NON_FINAL_STATUSES = "userBroadcast.updateNonFinalStatuses";
    public static final String USER_BROADCAST_FIND_READ_IDS_BY_USER_ID = "userBroadcast.findReadBroadcastIdsByUserId";
    public static final String USER_BROADCAST_FIND_UNREAD_BY_USER_ID = "userBroadcast.findUnreadPendingDeliveredByUserId";
    public static final String USER_BROADCAST_DELETE_UNREAD_BY_BROADCAST_ID = "userBroadcast.deleteUnreadByBroadcastId";

    // ===============================================
    // broadcast_statistics queries
    // ===============================================
    public static final String STATS_SAVE = "stats.save";
    public static final String STATS_FIND_BY_BROADCAST_ID = "stats.findByBroadcastId";
    public static final String STATS_INCREMENT_DELIVERED = "stats.incrementDelivered";
    public static final String STATS_INCREMENT_READ = "stats.incrementRead";
    public static final String STATS_INCREMENT_DELIVERED_AND_TARGETED = "stats.incrementDeliveredAndTargeted";

    // ===============================================
    // outbox_events queries
    // ===============================================
    public static final String OUTBOX_SAVE = "outbox.save";
    public static final String OUTBOX_BATCH_SAVE = "outbox.batchSave";
    public static final String OUTBOX_FIND_AND_LOCK = "outbox.findAndLock";
    public static final String OUTBOX_DELETE_BY_IDS_TEMPLATE = "outbox.deleteByIds.template";

    // ===============================================
    // dlt_messages queries
    // ===============================================
    public static final String DLT_SAVE = "dlt.save";
    public static final String DLT_FIND_ALL = "dlt.findAll";
    public static final String DLT_FIND_BY_ID = "dlt.findById";
    public static final String DLT_DELETE_BY_ID = "dlt.deleteById";
    public static final String DLT_DELETE_ALL = "dlt.deleteAll";
}