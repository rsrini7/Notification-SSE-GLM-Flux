package com.example.broadcast.admin.service;

// Make sure to import your admin DTOs and mapper
import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.admin.mapper.AdminBroadcastMapper;
import com.example.broadcast.shared.aspect.Monitored;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.BroadcastStatistics;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
@Monitored("service")
public class BroadcastQueryService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final AdminBroadcastMapper adminBroadcastMapper; // Use the new admin mapper

    public BroadcastResponse getBroadcast(Long id) {
        log.info("Querying for broadcast with stats by ID: {}", id);
        BroadcastMessage message = broadcastRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
        return enrichBroadcastWithMessageStats(message);
    }

    public List<BroadcastResponse> getActiveBroadcasts() {
        log.info("Querying for all active broadcasts with stats.");
        return broadcastRepository.findActiveBroadcasts().stream()
                .map(this::enrichBroadcastWithMessageStats)
                .collect(Collectors.toList());
    }

    public List<BroadcastResponse> getScheduledBroadcasts() {
        log.info("Querying for all scheduled broadcasts with stats.");
        return broadcastRepository.findScheduledBroadcasts().stream()
                .map(this::enrichBroadcastWithMessageStats)
                .collect(Collectors.toList());
    }

    public List<BroadcastResponse> getAllBroadcasts() {
        log.info("Querying for all broadcasts with stats.");
        return broadcastRepository.findAllOrderedByCreatedAtDesc().stream()
                .map(this::enrichBroadcastWithMessageStats)
                .collect(Collectors.toList());
    }

    private BroadcastResponse enrichBroadcastWithMessageStats(BroadcastMessage message) {
        BroadcastStatistics stats = broadcastStatisticsRepository.findByBroadcastId(message.getId())
                .orElseGet(() -> BroadcastStatistics.builder().totalTargeted(0).totalDelivered(0).totalRead(0).build());

        BroadcastResponse response = adminBroadcastMapper.toBroadcastResponse(message, stats.getTotalTargeted());
        response.setTotalDelivered(stats.getTotalDelivered());
        response.setTotalRead(stats.getTotalRead());
        return response;
    }

    public List<UserBroadcastMessage> getBroadcastDeliveries(Long broadcastId) {
        log.info("Retrieving delivery details for broadcast ID: {}", broadcastId);
        return userBroadcastRepository.findByBroadcastId(broadcastId);
    }
}