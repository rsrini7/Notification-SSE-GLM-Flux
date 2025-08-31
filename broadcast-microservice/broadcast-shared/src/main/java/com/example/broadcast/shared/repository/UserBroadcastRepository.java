package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.model.UserBroadcastMessage;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UserBroadcastRepository extends CrudRepository<UserBroadcastMessage, Long> {

    // Derived Queries
    Optional<UserBroadcastMessage> findByUserIdAndBroadcastId(String userId, Long broadcastId);
    List<UserBroadcastMessage> findByBroadcastId(Long broadcastId);

    // Custom Queries
    @Query("""
        SELECT ubm.* FROM user_broadcast_messages ubm
        JOIN broadcast_messages bm ON ubm.broadcast_id = bm.id
        WHERE ubm.user_id = :userId
          AND bm.status = 'ACTIVE'
          AND ubm.read_status = 'UNREAD'
          AND ubm.delivery_status IN ('PENDING', 'DELIVERED')
        ORDER BY bm.created_at DESC
    """)
    List<UserBroadcastMessage> findUnreadPendingDeliveredByUserId(@Param("userId") String userId);

    @Query("SELECT broadcast_id FROM user_broadcast_messages WHERE user_id = :userId AND read_status = 'READ'")
    List<Long> findReadBroadcastIdsByUserId(@Param("userId") String userId);
    
    @Modifying
    @Query("UPDATE user_broadcast_messages SET read_status = 'READ', read_at = :readAt, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND read_status = 'UNREAD'")
    int markAsRead(@Param("id") Long id, @Param("readAt") OffsetDateTime readAt);

    @Modifying
    @Query("UPDATE user_broadcast_messages SET delivery_status = :status, delivered_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = :id AND delivery_status = 'PENDING'")
    int updateDeliveryStatus(@Param("id") Long id, @Param("status") String status);

    @Modifying
    @Query("UPDATE user_broadcast_messages SET delivery_status = :newStatus, updated_at = CURRENT_TIMESTAMP WHERE broadcast_id = :broadcastId AND delivery_status IN ('PENDING', 'DELIVERED')")
    int updateNonFinalStatusesByBroadcastId(@Param("broadcastId") Long broadcastId, @Param("newStatus") String newStatus);

    @Modifying
    @Query("DELETE FROM user_broadcast_messages WHERE broadcast_id = :broadcastId AND read_status <> 'READ'")
    int deleteUnreadMessagesByBroadcastId(@Param("broadcastId") Long broadcastId);
}