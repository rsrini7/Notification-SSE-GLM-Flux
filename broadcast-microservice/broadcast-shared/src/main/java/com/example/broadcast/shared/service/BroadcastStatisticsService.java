package com.example.broadcast.shared.service;

import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
@Monitored("service")
public class BroadcastStatisticsService {

    private final BroadcastStatisticsRepository broadcastStatisticsRepository;

    @Transactional
    public void initializeStatistics(Long broadcastId, int totalTargeted) {
        log.info("Initializing statistics in a new transaction for broadcast ID {} with {} targeted users.", broadcastId, totalTargeted);
        BroadcastStatistics stats = BroadcastStatistics.builder()
                .broadcastId(broadcastId)
                .totalTargeted(totalTargeted)
                .totalDelivered(0)
                .totalRead(0)
                .totalFailed(0)
                .calculatedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build();
        broadcastStatisticsRepository.save(stats);
    }
}