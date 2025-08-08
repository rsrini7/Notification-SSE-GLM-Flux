package com.example.broadcast.user.service;

import com.example.broadcast.shared.config.AppProperties;
import com.example.broadcast.user.dto.UserBroadcastResponse;
import com.example.broadcast.shared.dto.cache.UserMessageInfo;
import com.example.broadcast.shared.exception.ResourceNotFoundException;
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

@Service
@RequiredArgsConstructor
@Slf4j
public class UserMessageService {

    private final UserBroadcastRepository userBroadcastRepository;
    private final BroadcastRepository broadcastRepository;
    private final BroadcastStatisticsRepository broadcastStatisticsRepository;
    private final MessageStatusService messageStatusService;
    private final CacheService cacheService;
    private final AppProperties appProperties;
    private final UserService userService;
    private final BroadcastMapper broadcastMapper;

    private final ConcurrentHashMap<Long, ReentrantLock> broadcastContentLocks = new ConcurrentHashMap<>();

    @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getUserMessages(String userId) {
        log.info("Getting messages for user: {}", userId);

        List<UserMessageInfo> cachedMessages = cacheService.getCachedUserMessages(userId);
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
            List<UserMessageInfo> messagesToCache = dbMessages.stream()
                .map(this::toUserMessageInfo)
                .collect(Collectors.toList());
            cacheService.cacheUserMessages(userId, messagesToCache);
        }

        return dbMessages;
    }

    @Transactional(readOnly = true)
    public List<UserBroadcastResponse> getGroupMessagesForUser(String userId) {
        List<String> userRoles = userService.getRolesForUser(userId);

        // Fetch broadcasts targeted to the user's specific roles
        List<BroadcastMessage> roleBroadcasts = userRoles.stream()
                .flatMap(role -> getActiveBroadcastsForRole(role).stream())
                .distinct()
                .collect(Collectors.toList());

        // Fetch broadcasts targeted to "ALL" users
        List<BroadcastMessage> allUserBroadcasts = getActiveBroadcastsForAll();

        // Combine, deduplicate, and map to the response DTO
        return Stream.concat(roleBroadcasts.stream(), allUserBroadcasts.stream())
                .distinct()
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
    public void markMessageAsRead(String userId, Long messageId) {
        log.info("Attempting to mark message as read: user={}, message={}", userId, messageId);
        UserBroadcastMessage userMessage = userBroadcastRepository.findById(messageId)
                .orElseThrow(() -> new ResourceNotFoundException("User message not found with ID: " + messageId));
        if (!userId.equals(userMessage.getUserId())) {
            throw new ResourceNotFoundException("Message does not belong to user: " + userId);
        }

        int updatedRows = userBroadcastRepository.markAsRead(messageId, ZonedDateTime.now(ZoneOffset.UTC));
        if (updatedRows > 0) {
            broadcastStatisticsRepository.incrementReadCount(userMessage.getBroadcastId());
            cacheService.updateMessageReadStatus(userId, userMessage.getBroadcastId());
            
            BroadcastMessage parentBroadcast = broadcastRepository.findById(userMessage.getBroadcastId())
                .orElseThrow(() -> new IllegalStateException("Cannot publish READ event. Original broadcast (ID: " + userMessage.getBroadcastId() + ") not found."));
            
            String topicName = getTopicNameForBroadcast(parentBroadcast);
            
            messageStatusService.publishReadEvent(userMessage.getBroadcastId(), userId, topicName);
            log.info("Successfully marked message {} as read for user {} and published READ event to topic {}.", messageId, userId, topicName);
        } else {
            log.warn("Message {} was already read for user {}. No action taken.", messageId, userId);
        }
    }
    
    private String getTopicNameForBroadcast(BroadcastMessage broadcast) {
        String targetType = broadcast.getTargetType();
        if (Constants.TargetType.SELECTED.name().equals(targetType)) {
            return appProperties.getKafka().getTopic().getNameSelected();
        }
        return appProperties.getKafka().getTopic().getNameGroup();
    }


    private UserMessageInfo toUserMessageInfo(UserBroadcastResponse response) {
        return new UserMessageInfo(
            response.getId(),
            response.getBroadcastId(),
            response.getDeliveryStatus(),
            response.getReadStatus(),
            response.getCreatedAt()
        );
    }

    private Optional<UserBroadcastResponse> enrichUserMessageInfo(UserMessageInfo info) {
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