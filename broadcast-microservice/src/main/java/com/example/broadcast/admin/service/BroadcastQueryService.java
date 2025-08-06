package com.example.broadcast.admin.service;

import com.example.broadcast.admin.dto.BroadcastResponse;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * A dedicated query service for retrieving broadcast information.
 * This class follows the Command Query Responsibility Segregation (CQRS) pattern
 * by handling all read-only operations, separating them from state-changing commands.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true) // All methods in this service are read-only
public class BroadcastQueryService {

    private final BroadcastRepository broadcastRepository;
    private final UserBroadcastRepository userBroadcastRepository;

    /**
     * Retrieves a single broadcast with its delivery statistics.
     *
     * @param id The ID of the broadcast.
     * @return The BroadcastResponse DTO.
     * @throws ResourceNotFoundException if no broadcast is found.
     */
    public BroadcastResponse getBroadcast(Long id) {
        log.info("Querying for broadcast with stats by ID: {}", id);
        return broadcastRepository.findBroadcastWithStatsById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Broadcast not found with ID: " + id));
    }

    /**
     * Retrieves all active broadcasts with their statistics.
     * @return A list of BroadcastResponse DTOs.
     */
    public List<BroadcastResponse> getActiveBroadcasts() {
        log.info("Querying for all active broadcasts with stats.");
        return broadcastRepository.findActiveBroadcastsWithStats();
    }

    /**
     * Retrieves all scheduled broadcasts with their statistics.
     * @return A list of BroadcastResponse DTOs.
     */
    public List<BroadcastResponse> getScheduledBroadcasts() {
        log.info("Querying for all scheduled broadcasts with stats.");
        return broadcastRepository.findScheduledBroadcastsWithStats();
    }

    /**
     * Retrieves all broadcasts regardless of status, with their statistics.
     * @return A list of BroadcastResponse DTOs.
     */
    public List<BroadcastResponse> getAllBroadcasts() {
        log.info("Querying for all broadcasts with stats.");
        return broadcastRepository.findAllBroadcastsWithStats();
    }

    /**
     * Retrieves the detailed delivery status for every user targeted by a specific broadcast.
     *
     * @param broadcastId The ID of the broadcast.
     * @return A list of UserBroadcastMessage entities.
     */
    public List<UserBroadcastMessage> getBroadcastDeliveries(Long broadcastId) {
        log.info("Retrieving delivery details for broadcast ID: {}", broadcastId);
        return userBroadcastRepository.findByBroadcastId(broadcastId);
    }
}