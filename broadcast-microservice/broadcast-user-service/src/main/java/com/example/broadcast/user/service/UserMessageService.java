package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.BroadcastContent;
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

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.ZoneOffset;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
        if (cachedInboxOpt.isPresent()  && !cachedInboxOpt.get().isEmpty()) {
            log.info("Cache HIT for user {} inbox.", userId);
            return reconstructInboxFromCache(cachedInboxOpt.get());
        }
        log.info("Cache MISS for user {} inbox. Fetching from database.", userId);

        return Mono.fromCallable(() -> {
            // Step 1: Fetch all relevant records and IDs from the database.
            List<UserBroadcastMessage> targetedMessages = userBroadcastRepository.findUnreadPendingDeliveredByUserId(userId);
            
            // Identify PENDING messages and update their status to DELIVERED
            List<UserBroadcastMessage> pendingMessagesToProcess = targetedMessages.stream()
                .filter(msg -> Constants.DeliveryStatus.PENDING.name().equals(msg.getDeliveryStatus()))
                .collect(Collectors.toList());
            
            Set<Long> readBroadcastIds = new HashSet<>(userBroadcastRepository.findReadBroadcastIdsByUserId(userId));
            List<BroadcastMessage> allTypeBroadcasts = broadcastRepository.findByStatusAndTargetType(
                Constants.BroadcastStatus.ACTIVE.name(), Constants.TargetType.ALL.name()
            ).stream().filter(b -> !readBroadcastIds.contains(b.getId())).collect(Collectors.toList());

            // Step 2: Collect ALL necessary broadcast IDs from BOTH lists first.
            Set<Long> allRequiredBroadcastIds = new HashSet<>();
            targetedMessages.forEach(msg -> allRequiredBroadcastIds.add(msg.getBroadcastId()));
            allTypeBroadcasts.forEach(msg -> allRequiredBroadcastIds.add(msg.getId()));

            // Step 3.1: Get the IDs of broadcasts already handled
            Set<Long> targetedBroadcastIds = targetedMessages.stream()
                .map(UserBroadcastMessage::getBroadcastId)
                .collect(Collectors.toSet());

            // Step 3.2: Fetch all required content in a single, unified call.
            Map<Long, BroadcastMessage> contentMap = getBroadcastContent(allRequiredBroadcastIds);

            // Step 4: Map targeted messages to the final response DTO.
            List<UserBroadcastResponse> finalInbox = targetedMessages.stream()
                    .map(msg -> {
                        BroadcastMessage broadcast = contentMap.get(msg.getBroadcastId());
                        return broadcast != null ? broadcastMapper.toUserBroadcastResponseFromEntity(msg, broadcast) : null;
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // Step 5: Filter the 'ALL' broadcasts to exclude any that are already in the inbox
            List<UserBroadcastResponse> allTypeResponses = allTypeBroadcasts.stream()
                .filter(broadcast -> !targetedBroadcastIds.contains(broadcast.getId()))
                .map(broadcast -> {
                    BroadcastMessage content = contentMap.get(broadcast.getId());
                    return content != null ? broadcastMapper.toUserBroadcastResponseFromEntity(null, content) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());


            finalInbox.addAll(allTypeResponses);

            // (Optional) Add this log for debugging to confirm the order before returning
            finalInbox.sort(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed());
            // log.debug("Final sorted inbox order for user {}: {}", userId, finalInbox.stream().map(UserBroadcastResponse::getBroadcastId).collect(Collectors.toList()));

            // Step 7: Create a new, correct cache entry from the final, sorted list.
            List<UserMessageInbox> inboxToCache = finalInbox.stream()
            .map(response -> new UserMessageInbox(
                    response.getUserMessageId(),
                    response.getBroadcastId(),
                    response.getDeliveryStatus(),
                    response.getReadStatus(),
                    response.getCreatedAt().toInstant().toEpochMilli()
                ))
                .collect(Collectors.toList());
            cacheService.cacheUserInbox(userId, inboxToCache);

            // Step 8: Asynchronously update stats for any newly delivered 'ALL' messages.
            if (!allTypeResponses.isEmpty()) {
                updateDeliveryStatsForOfflineUsers(allTypeResponses);
            }

            if (!pendingMessagesToProcess.isEmpty()) {
                processPendingMessagesAsynchronously(pendingMessagesToProcess);
            }

            log.info("Assembled and cached {} total messages for user {}", finalInbox.size(), userId);
            return finalInbox;

        }).subscribeOn(jdbcScheduler);
    }

    /**
     *  Runs in a separate thread to update statuses without blocking the user response.
     */
    @Async
    public void processPendingMessagesAsynchronously(List<UserBroadcastMessage> pendingMessages) {
        if (pendingMessages.isEmpty()) {
            return;
        }
        log.info("Asynchronously updating status for {} PENDING messages.", pendingMessages.size());
        for (UserBroadcastMessage message : pendingMessages) {
            try {
                // This call now runs in its own independent transaction due to the REQUIRES_NEW fix
                messageStatusService.updateMessageToDelivered(message.getId(), message.getBroadcastId());
            } catch (Exception e) {
                log.error("Error updating pending message {} for broadcast {} asynchronously.", message.getId(), message.getBroadcastId(), e);
            }
        }
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
                        // If the broadcast is no longer active (i.e., it was cancelled/expired),
                        // treat it as if the content was not found.
                        if (content != null && Constants.BroadcastStatus.ACTIVE.name().equals(content.getStatus())) {
                            return broadcastMapper.toUserBroadcastResponseFromCache(inboxItem, content);
                        }
                        return null; // Discard if content is null OR not ACTIVE
                    })
                    .filter(Objects::nonNull)
                    .sorted(Comparator.comparing(UserBroadcastResponse::getBroadcastCreatedAt).reversed())
                    .collect(Collectors.toList());
            
            return reconstructedList;
        }).subscribeOn(jdbcScheduler);
    }
    
    /**
     * Performs a bulk fetch for broadcast content, implementing a read-through cache pattern.
     * It first attempts to retrieve items from the Geode cache. For any items not found
     * in the cache, it fetches them from the database in a single batch query and then
     * primes the cache for subsequent requests.
     *
     * @param broadcastIds A Set of broadcast IDs to retrieve.
     * @return A Map of broadcast IDs to their corresponding BroadcastMessage entities.
     */
    private Map<Long, BroadcastMessage> getBroadcastContent(Set<Long> broadcastIds) {
        if (broadcastIds == null || broadcastIds.isEmpty()) {
            return Collections.emptyMap();
        }

        final Map<Long, BroadcastMessage> resultMap = new ConcurrentHashMap<>();
        final List<Long> cacheMissIds = new ArrayList<>();

        // 1. First Pass: Check the cache for each ID.
        // Collect hits and identify all cache misses.
        for (Long id : broadcastIds) {
            Optional<BroadcastContent> cachedDtoOpt = cacheService.getBroadcastContent(id);
            if (cachedDtoOpt.isPresent()) {
                resultMap.put(id, broadcastMapper.toBroadcastMessage(cachedDtoOpt.get()));
            } else {
                cacheMissIds.add(id);
            }
        }

        // 2. Database Fetch: If there were any cache misses, fetch them all in one go.
        if (!cacheMissIds.isEmpty()) {
            log.info("Cache miss for {} broadcast content items. Fetching from DB.", cacheMissIds.size());
            
            Iterable<BroadcastMessage> messagesFromDb = broadcastRepository.findAllById(cacheMissIds);

            // 3. Populate Results & Prime Cache: Add DB results to the map and update the cache.
            for (BroadcastMessage messageFromDb : messagesFromDb) {
                resultMap.put(messageFromDb.getId(), messageFromDb);
                // Prime the cache so the next request for this ID is a hit.
                cacheService.cacheBroadcastContent(broadcastMapper.toBroadcastContentDTO(messageFromDb));
            }
        }
        return resultMap;
    }

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void updateDeliveryStatsForOfflineUsers(List<UserBroadcastResponse> newlyDeliveredBroadcasts) {
        if (newlyDeliveredBroadcasts.isEmpty()) {
            return;
        }
        log.info("Asynchronously updating delivery stats for {} broadcasts for a reconnected user.", newlyDeliveredBroadcasts.size());
        for (UserBroadcastResponse broadcast : newlyDeliveredBroadcasts) {
            broadcastStatisticsRepository.incrementDeliveredCount(broadcast.getBroadcastId(), 1);
        }
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
            int updatedRows = userBroadcastRepository.markAsRead(existingMessage.getId(), OffsetDateTime.now(ZoneOffset.UTC));
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
                     .readAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();
            userBroadcastRepository.save(newMessage);
        }
        broadcastStatisticsRepository.incrementReadCount(broadcastId);
        messageStatusService.publishReadEvent(broadcastId, userId);
        log.info("Successfully processed 'mark as read' for broadcast {} for user {} and published READ event.", broadcastId, userId);
    }


     /**
     * Asynchronously creates a 'DELIVERED' record for a user who has just received
     * a fan-out-on-read ('ALL') broadcast via SSE.
     * This runs in a new transaction to avoid blocking the real-time delivery path.
     * It is idempotent and will not create a duplicate record.
     *
     * @param userId The ID of the user who received the message.
     * @param broadcastId The ID of the broadcast that was delivered.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDeliveryForFanOutOnRead(String userId, Long broadcastId) {
        try {
            // Idempotency check: Don't create a record if one already exists.
            if (userBroadcastRepository.findByUserIdAndBroadcastId(userId, broadcastId).isPresent()) {
                log.info("User message record for user {} and broadcast {} already exists. No new record created.", userId, broadcastId);
                return;
            }

            log.info("Creating 'DELIVERED' record for user {} for 'ALL' broadcast {}", userId, broadcastId);
            UserBroadcastMessage deliveredMessage = UserBroadcastMessage.builder()
                    .userId(userId)
                    .broadcastId(broadcastId)
                    .deliveryStatus(Constants.DeliveryStatus.DELIVERED.name())
                    .readStatus(Constants.ReadStatus.UNREAD.name())
                    .deliveredAt(OffsetDateTime.now(ZoneOffset.UTC))
                    .build();

            userBroadcastRepository.save(deliveredMessage);

            // Atomically increment the central delivery counter
            broadcastStatisticsRepository.incrementDeliveredCount(broadcastId);

        } catch (DataIntegrityViolationException e) {
            // This handles the rare race condition where another thread creates the record
            // between our check and our save operation.
            log.warn("Caught race condition when creating delivered record for user {} and broadcast {}. Ignoring.", userId, broadcastId);
        } catch (Exception e) {
            log.error("Failed to record 'DELIVERED' status for user {} and broadcast {}", userId, broadcastId, e);
        }
    }
}