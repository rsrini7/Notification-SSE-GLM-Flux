package com.example.broadcast.shared.service.cache;

import com.example.broadcast.shared.dto.MessageDeliveryEvent;
import com.example.broadcast.shared.dto.cache.*;
import com.example.broadcast.shared.model.BroadcastMessage; // Import BroadcastMessage

import java.util.List;
import java.util.Map;
import java.util.Optional; // Import Optional

public interface CacheService {

    void registerUserConnection(String userId, String sessionId, String podId);
    void unregisterUserConnection(String userId, String sessionId);

    void updateUserActivity(String userId);

    boolean isUserOnline(String userId);

    UserConnectionInfo getUserConnectionInfo(String userId);

    List<String> getOnlineUsers();
    void cacheUserMessages(String userId, List<UserMessageInfo> messages);

    List<UserMessageInfo> getCachedUserMessages(String userId);

    void addMessageToUserCache(String userId, UserMessageInfo message);

    void removeMessageFromUserCache(String userId, Long broadcastId);
    void cachePendingEvent(MessageDeliveryEvent event);

    List<MessageDeliveryEvent> getPendingEvents(String userId);

    void removePendingEvent(String userId, Long broadcastId);
    
    void clearPendingEvents(String userId);
    
    void updateMessageReadStatus(String userId, Long broadcastId);
    void cacheBroadcastStats(String statsKey, BroadcastStatsInfo stats);

    BroadcastStatsInfo getCachedBroadcastStats(String statsKey);

    Map<String, Object> getCacheStats();

    Optional<BroadcastMessage> getBroadcastContent(Long broadcastId);

    void cacheBroadcastContent(BroadcastMessage broadcast);
}