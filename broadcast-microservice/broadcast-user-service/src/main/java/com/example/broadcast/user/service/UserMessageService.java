package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.user.UserBroadcastResponse;
import com.example.broadcast.shared.dto.cache.PersistentUserMessageInfo;
import com.example.broadcast.shared.model.BroadcastMessage;
import com.example.broadcast.shared.model.UserBroadcastMessage;
import com.example.broadcast.shared.repository.BroadcastRepository;
import com.example.broadcast.shared.repository.BroadcastStatisticsRepository;
import com.example.broadcast.shared.repository.UserBroadcastRepository;
import com.example.broadcast.shared.service.cache.CacheService;
import com.example.broadcast.shared.util.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.example.broadcast.shared.service.MessageStatusService;
import com.example.broadcast.shared.service.UserService;
import com.example.broadcast.shared.mapper.BroadcastMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.Set;
import java.util.HashSet;


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

    private final ConcurrentHashMap<Long, ReentrantLock> broadcastContentLocks = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);

        List<PersistentUserMessageInfo> cachedMessages = cacheService.getCachedUserMessages(userId);
        if (cachedMessages != null && !cachedMessages.isEmpty()) {
            log.info("Cache HIT for user messages: {}", userId);
            return cachedMessages.stream()
                .map(this::enrichUserMessageInfo)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toList());
        }

        log.info("Cache MISS for user messages: {}. Fetching from database.", userId);
        List<UserBroadcastResponse> dbMessages = userBroadcastRepository.findUserMessagesByUserId(userId);

        if (!dbMessages.isEmpty()) {
            List<PersistentUserMessageInfo> messagesToCache = dbMessages.stream()
                .map(this::toUserMessageInfo)
                .collect(Collectors.toList());
            cacheService.cacheUserMessages(userId, messagesToCache);
        }

        return dbMessages;
    }

   @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getActiveBroadcastsForUser(String userId) {
        // 1. Get a list of broadcast IDs the user has already interacted with (e.g., marked as read).
        // These should not be re-sent.
        List<Long> processedBroadcastIds = userBroadcastRepository.findActiveBroadcastIdsByUserId(userId);
        Set<Long> processedBroadcastIdSet = new HashSet<>(processedBroadcastIds);

        // 2. Fetch broadcasts targeted to the user's roles.
        List<String> userRoles = userService.getRolesForUser(userId);
        List<BroadcastMessage> roleBroadcasts = userRoles.stream()
            .flatMap(role -> getActiveBroadcastsForRole(role).stream())
            .distinct()
            .collect(Collectors.toList());

        // 3. Fetch broadcasts targeted to "ALL" users.
        List<BroadcastMessage> allUserBroadcasts = getActiveBroadcastsForAll();

        // 4. Fetch broadcasts where this specific user was 'SELECTED'.
        List<BroadcastMessage> selectedBroadcasts = broadcastRepository.findActiveSelectedBroadcastsForUser(userId);

        // 5. Combine all three lists, deduplicate, filter out already processed messages, and map to the response DTO.
        return Stream.of(roleBroadcasts, allUserBroadcasts, selectedBroadcasts)
                .flatMap(List::stream)
                .distinct()
                .filter(broadcast -> !processedBroadcastIdSet.contains(broadcast.getId()))
                .map(broadcast -> broadcastMapper.toUserBroadcastResponse(null, broadcast))
                .collect(Collectors.toList());
    }

    private List<BroadcastMessage> getActiveBroadcastsForRole(String role) {
        final String cacheKey = "ROLE:" + role;
        List<BroadcastMessage> cachedBroadcasts = cacheService.getActiveGroupBroadcasts(cacheKey);
        
        if (cachedBroadcasts != null) {
            log.debug("Cache HIT for active broadcasts for role: {}", role);
            return cachedBroadcasts;
        }

        log.warn("Cache MISS for active broadcasts for role: {}. Fetching from DB.", role);
        List<BroadcastMessage> dbBroadcasts = broadcastRepository.findActiveBroadcastsByTargetTypeAndIds("ROLE", List.of(role));
        cacheService.cacheActiveGroupBroadcasts(cacheKey, dbBroadcasts);
        return dbBroadcasts;
    }

    private List<BroadcastMessage> getActiveBroadcastsForAll() {
        final String cacheKey = "ALL";
        List<BroadcastMessage> cachedBroadcasts = cacheService.getActiveGroupBroadcasts(cacheKey);

        if (cachedBroadcasts != null) {
            log.debug("Cache HIT for active broadcasts for ALL users");
            return cachedBroadcasts;
        }

        log.warn("Cache MISS for active broadcasts for ALL users. Fetching from DB.");
        List<BroadcastMessage> dbBroadcasts = broadcastRepository.findActiveBroadcastsByTargetType("ALL");
        cacheService.cacheActiveGroupBroadcasts(cacheKey, dbBroadcasts);
        return dbBroadcasts;
    }
    
    public List<UserBroadcastResponse> getUnreadMessages(String userId) {
        log.info("Getting unread messages for user: {}", userId);
        return userBroadcastRepository.findUserMessagesByUserId(userId);
    }

    @Transactional
    public void markMessageAsRead(String userId, Long broadcastId) {
        log.info("Attempting to mark broadcast {} as read for user {}", broadcastId, userId);

        Optional<UserBroadcastMessage> userMessageOpt = userBroadcastRepository
                .findByUserIdAndBroadcastId(userId, broadcastId);

        if (userMessageOpt.isPresent()) {
            UserBroadcastMessage existingMessage = userMessageOpt.get();
            if (Constants.ReadStatus.READ.name().equals(existingMessage.getReadStatus())) {
                log.warn("Message for broadcast {} was already read for user {}. No action taken.", broadcastId, userId);
                return;
            }
            
            // This returns the number of rows updated.
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
        cacheService.removeMessageFromUserCache(userId, broadcastId);
        cacheService.removePendingEvent(userId, broadcastId);
        messageStatusService.publishReadEvent(broadcastId, userId);
        
        log.info("Successfully processed 'mark as read' for broadcast {} for user {} and published READ event.", broadcastId, userId);
    }
    
    private PersistentUserMessageInfo toUserMessageInfo(UserBroadcastResponse response) {
        return new PersistentUserMessageInfo(
            response.getId(),
            response.getBroadcastId(),
            response.getDeliveryStatus(),
            response.getReadStatus(),
            response.getCreatedAt()
        );
    }

    private Optional<UserBroadcastResponse> enrichUserMessageInfo(PersistentUserMessageInfo info) {
        Optional<BroadcastMessage> broadcastOpt = cacheService.getBroadcastContent(info.getBroadcastId());

        if (broadcastOpt.isEmpty()) {
            ReentrantLock lock = broadcastContentLocks.computeIfAbsent(info.getBroadcastId(), k -> new ReentrantLock());
            lock.lock();
            try {
                broadcastOpt = cacheService.getBroadcastContent(info.getBroadcastId());
                if (broadcastOpt.isEmpty()) {
                    log.warn("Broadcast content for ID {} was not in cache. Fetching from DB.", info.getBroadcastId());
                    broadcastOpt = broadcastRepository.findById(info.getBroadcastId());
                    broadcastOpt.ifPresent(cacheService::cacheBroadcastContent);
                }
            } finally {
                lock.unlock();
                broadcastContentLocks.remove(info.getBroadcastId(), lock);
            }
        }

        if (broadcastOpt.isEmpty()) {
            log.error("Data integrity issue: Could not find broadcast content for ID {} in cache or DB.", info.getBroadcastId());
            return Optional.empty();
        }

        BroadcastMessage broadcast = broadcastOpt.get();
        
        UserBroadcastMessage messageStub = new UserBroadcastMessage();
        messageStub.setId(info.getMessageId());
        messageStub.setBroadcastId(info.getBroadcastId());
        messageStub.setUserId(broadcast.getSenderId());
        messageStub.setDeliveryStatus(info.getDeliveryStatus());
        messageStub.setReadStatus(info.getReadStatus());
        messageStub.setCreatedAt(info.getCreatedAt());

        return Optional.of(broadcastMapper.toUserBroadcastResponse(messageStub, broadcast));
    }
}