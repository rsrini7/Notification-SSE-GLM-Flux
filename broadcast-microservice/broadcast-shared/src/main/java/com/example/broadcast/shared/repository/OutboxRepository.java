package com.example.broadcast.shared.repository;

import com.example.broadcast.shared.model.OutboxEvent;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRepository extends CrudRepository<OutboxEvent, UUID> {

    // Note: saveAll(...) and deleteAllById(...) are inherited from CrudRepository
    // and replace the old batchSave and deleteByIds methods.
    
    @Query("""
        SELECT * FROM outbox_events
        ORDER BY created_at
        LIMIT :limit
        FOR UPDATE SKIP LOCKED
    """)
    List<OutboxEvent> findAndLockUnprocessedEvents(@Param("limit") int limit);
}