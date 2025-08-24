package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.cache.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final UserService userService;
    private final BroadcastMapper broadcastMapper;
    private final Scheduler jdbcScheduler;

    @Transactional(readOnly = true)
    public Mono<List<UserBroadcastResponse>> getUserMessages(String userId) {
        log.info("Getting all unread messages for user: {}", userId);
        
        // getActiveBroadcastsForUser now fetches ALL, ROLE, and SELECTED messages, and filters out read ones.
        return this.getActiveBroadcastsForUser(userId)
            .map(allUnreadBroadcasts -> {
                log.info("Assembled a total of {} messages for user {}", allUnreadBroadcasts.size(), userId);
                return allUnreadBroadcasts.stream()
                    .map(broadcast -> broadcastMapper.toUserBroadcastResponse(null, broadcast))
                    .sorted(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed())
                    .collect(Collectors.toList());
            });
    }

    @Transactional(readOnly = true)
    public Mono<List<BroadcastMessage>> getActiveBroadcastsForUser(String userId) {
        return Mono.fromCallable(() -> {
            List<Long> readBroadcastIds = userBroadcastRepository.findReadBroadcastIdsByUserId(userId);
            Set<Long> readBroadcastIdSet = new HashSet<>(readBroadcastIds);

            List<String> userRoles = userService.getRolesForUser(userId);
            List<BroadcastMessage> roleBroadcasts = userRoles.stream()
                    .flatMap(role -> getActiveBroadcastsForRole(role).stream())
                    .distinct()
                    .collect(Collectors.toList());
            List<BroadcastMessage> allUserBroadcasts = getActiveBroadcastsForAll();
            List<BroadcastMessage> selectedBroadcasts = broadcastRepository.findActiveSelectedBroadcastsForUser(userId);
            
            return Stream.of(roleBroadcasts, allUserBroadcasts, selectedBroadcasts)
                    .flatMap(List::stream)
                    .distinct()
                    .filter(broadcast -> !readBroadcastIdSet.contains(broadcast.getId()))
                    .collect(Collectors.toList());
        }).subscribeOn(jdbcScheduler);
    }

    private List<BroadcastMessage> getActiveBroadcastsForRole(String role) {
        final String cacheKey = "ROLE:" + role;
        List<BroadcastMessage> cachedBroadcasts = cacheService.getActiveGroupBroadcasts(cacheKey);
        if (cachedBroadcasts != null) {
            log.debug("[CACHE_HIT] Active broadcasts for role='{}' found in cache", role);
            return cachedBroadcasts;
        }
        log.info("[CACHE_MISS] Active broadcasts for role='{}' not in cache. Fetching from DB.", role);
        List<BroadcastMessage> dbBroadcasts = broadcastRepository.findActiveBroadcastsByTargetTypeAndIds("ROLE", List.of(role));
        cacheService.cacheActiveGroupBroadcasts(cacheKey, dbBroadcasts);
        return dbBroadcasts;
    }

    private List<BroadcastMessage> getActiveBroadcastsForAll() {
        final String cacheKey = "ALL";
        List<BroadcastMessage> cachedBroadcasts = cacheService.getActiveGroupBroadcasts(cacheKey);
        if (cachedBroadcasts != null) {
            log.debug("[CACHE_HIT] Active broadcasts for 'ALL' users found in cache");
            return cachedBroadcasts;
        }
        log.info("[CACHE_MISS] Active broadcasts for 'ALL' users not in cache. Fetching from DB.", "ALL");
        List<BroadcastMessage> dbBroadcasts = broadcastRepository.findActiveBroadcastsByTargetType("ALL");
        cacheService.cacheActiveGroupBroadcasts(cacheKey, dbBroadcasts);
        return dbBroadcasts;
    }
    
    @Transactional
    public void markMessageAsRead(String userId, Long broadcastId) {
        log.info("Attempting to mark broadcast {} as read for user {}", broadcastId, userId);
        Optional<UserBroadcastMessage> userMessageOpt = userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcastId);
        if (userMessageOpt.isPresent()) {
            UserBroadcastMessage existingMessage = userMessageOpt.get();
            if (Constants.ReadStatus.READ.name().equals(existingMessage.getReadStatus())) {
                log.warn("Message for broadcast {} was already read for user {}. No action taken.", broadcastId, userId);
                return;
            }
            int updatedRows = userBroadcastRepository.markAsRead(existingMessage.getId(), ZonedDateTime.now(ZoneOffset.UTC));
            if (updatedRows == 0) {
                log.warn("Message for broadcast {} was already read for user {} (concurrent update). No action taken.", broadcastId, userId);
                return;
            }
        } else {
            log.info("No existing message record for user {}, broadcast {}. Creating a new one.", userId, broadcastId);
            UserBroadcastMessage newMessage = UserBroadcastMessage.builder()
                    .userId(userId)
                    .broadcastId(broadcastId)
                    .deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
                    .readStatus(Constants.ReadStatus.READ.name())
                    .readAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
            userBroadcastRepository.save(newMessage);
        }
        broadcastStatisticsRepository.incrementReadCount(broadcastId);
        cacheService.removePendingEvent(userId, broadcastId);
        messageStatusService.publishReadEvent(broadcastId, userId);
        log.info("Successfully processed 'mark as read' for broadcast {} for user {} and published READ event.", broadcastId, userId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processAndCountGroupMessageDelivery(String userId, BroadcastMessage broadcast) {
        if (broadcast == null || userId == null) return;
        broadcastStatisticsRepository.incrementDeliveredCount(broadcast.getId());
        log.info("Counted delivery of group broadcast {} to user {}", broadcast.getId(), userId);
    }
}