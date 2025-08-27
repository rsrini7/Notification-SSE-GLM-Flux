package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.mapper.BroadcastMapper;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.util.Constants;
import com.example.broadcast.user.service.cache.CacheService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
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
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final BroadcastMapper broadcastMapper;
    private final Scheduler jdbcScheduler;

    @Transactional(readOnly = true)
    public Mono<List<UserBroadcastResponse>> getUserMessages(String userId) {
        log.info("Assembling inbox for user: {}", userId);

        return Mono.fromCallable(() -> {
            // Step 1: Directly fetch all unread targeted messages for the user.
            // This now covers SELECTED, ROLE, and PRODUCT types efficiently.
            List<UserBroadcastMessage> unreadTargetedMessages = userBroadcastRepository.findUnreadByUserId(userId);

            // Step 2: Fetch all active 'ALL' type broadcasts (fan-out on read).
            List<BroadcastMessage> allTypeBroadcasts = getActiveBroadcastsForAll();

            // Step 3: Get IDs of 'ALL' broadcasts the user has already marked as read to filter them out.
            Set<Long> readBroadcastIds = new HashSet<>(userBroadcastRepository.findReadBroadcastIdsByUserId(userId));

            // Step 4: Convert the unread targeted messages into response DTOs.
            // This uses a read-through cache pattern for performance.
            Stream<UserBroadcastResponse> targetedResponses = unreadTargetedMessages.stream()
                .map(msg -> {
                    BroadcastMessage broadcast = cacheService.getBroadcastContent(msg.getBroadcastId())
                        .orElseGet(() -> broadcastRepository.findById(msg.getBroadcastId()).orElse(null));
                    return broadcast != null ? broadcastMapper.toUserBroadcastResponse(msg, broadcast) : null;
                })
                .filter(Objects::nonNull);

            // Step 5: Convert the unread 'ALL' type broadcasts into response DTOs.
            Stream<UserBroadcastResponse> allTypeResponses = allTypeBroadcasts.stream()
                .filter(broadcast -> !readBroadcastIds.contains(broadcast.getId()))
                .map(broadcast -> broadcastMapper.toUserBroadcastResponse(null, broadcast));

            // Step 6: Combine both streams, sort by creation date, and collect into the final list.
            List<UserBroadcastResponse> finalInbox = Stream.concat(targetedResponses, allTypeResponses)
                .sorted(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed())
                .collect(Collectors.toList());
            
            log.info("Assembled a total of {} messages for user {}", finalInbox.size(), userId);
            return finalInbox;

        }).subscribeOn(jdbcScheduler);
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

    @Transactional
    public void persistGroupMessageDelivery(String userId, BroadcastMessage broadcast) {
        if (broadcast == null || userId == null) return;

        // Check if a record already exists for this user and broadcast to avoid errors.
        Optional<UserBroadcastMessage> existingMessage = userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcast.getId());

        // If no record exists, it's the first time this user is seeing this message.
        if (existingMessage.isEmpty()) {
            log.info("First-time delivery of 'ALL' broadcast {} to user {}. Creating persistent 'DELIVERED' record.", broadcast.getId(), userId);
            
            // Create a record to track that this message has been delivered.
            UserBroadcastMessage newMessage = UserBroadcastMessage.builder()
                    .userId(userId)
                    .broadcastId(broadcast.getId())
                    .deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
                    .readStatus(Constants.ReadStatus.UNREAD.name())
                    .deliveredAt(ZonedDateTime.now(ZoneOffset.UTC))
                    .build();
            userBroadcastRepository.save(newMessage);
        }
    }

}