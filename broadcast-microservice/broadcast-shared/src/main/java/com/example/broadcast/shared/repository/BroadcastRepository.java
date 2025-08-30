package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.dto.admin.BroadcastResponse;
import com.example.broadcast.shared.model.BroadcastMessage;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface BroadcastRepository extends CrudRepository<BroadcastMessage, Long> {

    // Simple Derived Query
    List<BroadcastMessage> findByStatusAndTargetType(String status, String targetType);

    // DTO Projections with custom @Query
    @Query("""
        SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                     COALESCE(s.total_delivered, 0) as total_delivered,
                     COALESCE(s.total_read, 0) as total_read
        FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
        WHERE b.id = :id
    """)
    Optional<BroadcastResponse> findWithStatsById(@Param("id") Long id);

    @Query("""
        SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                     COALESCE(s.total_delivered, 0) as total_delivered,
                     COALESCE(s.total_read, 0) as total_read
        FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
        ORDER BY b.created_at DESC
    """)
    List<BroadcastResponse> findAllWithStats();

    @Query("""
        SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                     COALESCE(s.total_delivered, 0) as total_delivered,
                     COALESCE(s.total_read, 0) as total_read
        FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
        WHERE b.status = 'ACTIVE' ORDER BY b.created_at DESC
    """)
    List<BroadcastResponse> findActiveBroadcastsWithStats();
    
    @Query("""
        SELECT b.*, COALESCE(s.total_targeted, 0) as total_targeted,
                     COALESCE(s.total_delivered, 0) as total_delivered,
                     COALESCE(s.total_read, 0) as total_read
        FROM broadcast_messages b LEFT JOIN broadcast_statistics s ON b.id = s.broadcast_id
        WHERE b.status = 'SCHEDULED' ORDER BY b.scheduled_at ASC
    """)
    List<BroadcastResponse> findScheduledBroadcastsWithStats();

    // Custom Queries for specific logic
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

    // Custom update query
    @Modifying
    @Query("UPDATE broadcast_messages SET status = :status, updated_at = CURRENT_TIMESTAMP WHERE id = :id")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}