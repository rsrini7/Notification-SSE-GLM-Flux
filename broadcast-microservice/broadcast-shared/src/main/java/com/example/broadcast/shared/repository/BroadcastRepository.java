package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.model.BroadcastMessage;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface BroadcastRepository extends CrudRepository<BroadcastMessage, Long> {

    // These queries now return the BroadcastMessage entity, not a DTO.
    @Query("SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' ORDER BY created_at DESC")
    List<BroadcastMessage> findActiveBroadcasts();

    @Query("SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' ORDER BY scheduled_at ASC")
    List<BroadcastMessage> findScheduledBroadcasts();
    
    @Query("SELECT * FROM broadcast_messages ORDER BY created_at DESC")
    List<BroadcastMessage> findAllOrderedByCreatedAtDesc();
    
    // The rest of the methods are unchanged as they already used BroadcastMessage
    List<BroadcastMessage> findByStatusAndTargetType(String status, String targetType);

    @Query("SELECT * FROM broadcast_messages WHERE status = 'ACTIVE' AND expires_at IS NOT NULL AND expires_at <= CAST(:now AS timestamptz)")
    List<BroadcastMessage> findExpiredBroadcasts(@Param("now") OffsetDateTime now);

    @Query("SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' AND target_type = 'PRODUCT' AND scheduled_at <= CAST(:cutoffTime AS timestamptz) FOR UPDATE SKIP LOCKED")
    List<BroadcastMessage> findScheduledProductBroadcastsWithinWindow(@Param("cutoffTime") OffsetDateTime cutoffTime);
    
    @Query("SELECT * FROM broadcast_messages WHERE status = 'SCHEDULED' AND target_type IN ('ALL', 'ROLE', 'SELECTED') AND scheduled_at <= CAST(:now AS timestamptz) ORDER BY scheduled_at LIMIT :limit FOR UPDATE SKIP LOCKED")
    List<BroadcastMessage> findAndLockScheduledFanOutBroadcasts(@Param("now") OffsetDateTime now, @Param("limit") int limit);
    
    @Query("SELECT * FROM broadcast_messages WHERE status = 'READY' AND (scheduled_at IS NULL OR scheduled_at <= CAST(:now AS timestamptz)) ORDER BY created_at LIMIT :limit FOR UPDATE SKIP LOCKED")
    List<BroadcastMessage> findAndLockReadyBroadcastsToProcess(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Query("SELECT * FROM broadcast_messages WHERE status IN ('CANCELLED', 'EXPIRED') AND updated_at < CAST(:cutoff AS timestamptz)")
    List<BroadcastMessage> findFinalizedBroadcastsForCleanup(@Param("cutoff") OffsetDateTime cutoff);

    @Modifying
    @Query("UPDATE broadcast_messages SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}