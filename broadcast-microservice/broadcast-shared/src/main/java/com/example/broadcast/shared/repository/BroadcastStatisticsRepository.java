package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.model.BroadcastStatistics;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BroadcastStatisticsRepository extends CrudRepository<BroadcastStatistics, Long> {

    // Derived Query
    Optional<BroadcastStatistics> findByBroadcastId(Long broadcastId);

    @Modifying
    @Query("UPDATE broadcast_statistics SET total_delivered = total_delivered + 1, calculated_at = CURRENT_TIMESTAMP WHERE broadcast_id = :broadcastId")
    int incrementDeliveredCount(@Param("broadcastId") Long broadcastId);

    @Modifying
    @Query("UPDATE broadcast_statistics SET total_read = total_read + 1, calculated_at = CURRENT_TIMESTAMP WHERE broadcast_id = :broadcastId")
    int incrementReadCount(@Param("broadcastId") Long broadcastId);

    @Modifying
    @Query("UPDATE broadcast_statistics SET total_delivered = total_delivered + :count, calculated_at = CURRENT_TIMESTAMP WHERE broadcast_id = :broadcastId")
    int incrementDeliveredAndTargetedCount(@Param("broadcastId") Long broadcastId, @Param("count") int count);
}