package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserMessageInbox;
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
import java.util.*;
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
    private final BroadcastMapper broadcastMapper;
    private final Scheduler jdbcScheduler;

    @Transactional(readOnly = true)
    public Mono<List<UserBroadcastResponse>> getUserMessages(String userId) {
        log.info("Assembling inbox for user: {}", userId);

        Optional<List<UserMessageInbox>> cachedInboxOpt = cacheService.getUserInbox(userId);
        if (cachedInboxOpt.isPresent()) {
            log.info("Cache HIT for user {} inbox.", userId);
            return reconstructInboxFromCache(cachedInboxOpt.get());
        }
        log.info("Cache MISS for user {} inbox. Fetching from database.", userId);

        return Mono.fromCallable(() -> {
            List<UserBroadcastMessage> unreadTargetedMessages = userBroadcastRepository.findUnreadByUserId(userId);
            List<BroadcastMessage> allTypeBroadcasts = broadcastRepository.findActiveBroadcastsByTargetType("ALL");
            Set<Long> readBroadcastIds = new HashSet<>(userBroadcastRepository.findReadBroadcastIdsByUserId(userId));

            List<UserMessageInbox> inboxToCache = new ArrayList<>();
            unreadTargetedMessages.forEach(msg -> inboxToCache.add(broadcastMapper.toUserMessageInbox(msg)));

            Map<Long, BroadcastMessage> contentMap = getBroadcastContent(unreadTargetedMessages.stream()
                    .map(UserBroadcastMessage::getBroadcastId).collect(Collectors.toSet()));

            Stream<UserBroadcastResponse> targetedResponses = unreadTargetedMessages.stream()
                    .map(msg -> {
                        BroadcastMessage broadcast = contentMap.get(msg.getBroadcastId());
                        return broadcast != null ? broadcastMapper.toUserBroadcastResponseFromEntity(msg, broadcast) : null;
                    })
                    .filter(Objects::nonNull);
            
            Stream<UserBroadcastResponse> allTypeResponses = allTypeBroadcasts.stream()
                    .filter(broadcast -> !readBroadcastIds.contains(broadcast.getId()))
                    .map(broadcast -> {
                        UserMessageInbox transientInbox = new UserMessageInbox(broadcast.getId(), broadcast.getId(), "DELIVERED", "UNREAD", broadcast.getCreatedAt());
                        inboxToCache.add(transientInbox);
                        return broadcastMapper.toUserBroadcastResponseFromCache(transientInbox, broadcast);
                    });

            List<UserBroadcastResponse> finalInbox = Stream.concat(targetedResponses, allTypeResponses)
                    .sorted(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed())
                    .collect(Collectors.toList());

            cacheService.cacheUserInbox(userId, inboxToCache);
            log.info("Assembled and cached {} messages for user {}", finalInbox.size(), userId);
            return finalInbox;

        }).subscribeOn(jdbcScheduler);
    }

    private Mono<List<UserBroadcastResponse>> reconstructInboxFromCache(List<UserMessageInbox> cachedInbox) {
        if (cachedInbox.isEmpty()) {
            return Mono.just(Collections.emptyList());
        }
        return Mono.fromCallable(() -> {
            Set<Long> broadcastIds = cachedInbox.stream().map(UserMessageInbox::getBroadcastId).collect(Collectors.toSet());
            Map<Long, BroadcastMessage> contentMap = getBroadcastContent(broadcastIds);

            List<UserBroadcastResponse> reconstructedList = cachedInbox.stream()
                    .map(inboxItem -> {
                        BroadcastMessage content = contentMap.get(inboxItem.getBroadcastId());
                        return content != null ? broadcastMapper.toUserBroadcastResponseFromCache(inboxItem, content) : null;
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed())
                    .collect(Collectors.toList());
            
            return reconstructedList;
        }).subscribeOn(jdbcScheduler);
    }
    
    private Map<Long, BroadcastMessage> getBroadcastContent(Set<Long> broadcastIds) {
        return broadcastIds.stream().collect(Collectors.toMap(
                id -> id,
                id -> cacheService.getBroadcastContent(id)
                        .orElseGet(() -> broadcastRepository.findById(id).orElse(null))
        ));
    }

    @Transactional
    public void markMessageAsRead(String userId, Long broadcastId) {
        log.info("Attempting to mark broadcast {} as read for user {}", broadcastId, userId);
        
        cacheService.evictUserInbox(userId);
        
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
        messageStatusService.publishReadEvent(broadcastId, userId);
        log.info("Successfully processed 'mark as read' for broadcast {} for user {} and published READ event.", broadcastId, userId);
    }
}