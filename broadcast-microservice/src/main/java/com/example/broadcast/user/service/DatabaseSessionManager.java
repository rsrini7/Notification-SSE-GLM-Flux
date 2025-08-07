package com.example.broadcast.user.service;

import com.example.broadcast.shared.dto.cache.UserSessionInfo;
import com.example.broadcast.shared.model.UserSession;
import com.example.broadcast.shared.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Profile("!redis")
@RequiredArgsConstructor
public class DatabaseSessionManager implements DistributedSessionManager {

    private final UserSessionRepository userSessionRepository;

    @Override
    @Transactional
    public void registerSession(String userId, String sessionId, String podId) {
        UserSession session = UserSession.builder()
                .userId(userId).sessionId(sessionId).podId(podId)
                .connectionStatus("ACTIVE")
                .connectedAt(ZonedDateTime.now(ZoneOffset.UTC))
                .lastHeartbeat(ZonedDateTime.now(ZoneOffset.UTC))
                .build();
        userSessionRepository.save(session);
    }

    @Override
    @Transactional
    public void removeSession(String userId, String sessionId, String podId) {
        userSessionRepository.markSessionInactive(sessionId, podId);
    }

    @Override
    @Transactional
    public void updateHeartbeats(String podId, Set<String> sessionIds) {
        if (!sessionIds.isEmpty()) {
            userSessionRepository.updateLastHeartbeatForActiveSessions(List.copyOf(sessionIds), podId);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> getStaleSessionIds(long thresholdTimestamp) {
        ZonedDateTime threshold = ZonedDateTime.ofInstant(java.time.Instant.ofEpochSecond(thresholdTimestamp), ZoneOffset.UTC);
        return userSessionRepository.findStaleSessions(threshold)
                .stream()
                .map(UserSession::getSessionId)
                .collect(Collectors.toSet());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserSessionInfo> getSessionDetails(String sessionId) {
        return userSessionRepository.findBySessionId(sessionId)
            .map(session -> new UserSessionInfo(session.getUserId(), session.getSessionId(), session.getPodId(), session.getLastHeartbeat()));
    }

    @Override
    @Transactional
    public void removeSessions(Set<String> sessionIds) {
        if (!sessionIds.isEmpty()) {
            userSessionRepository.markSessionsInactive(List.copyOf(sessionIds));
        }
    }
    
    @Override
    @Transactional
    public void markSessionsInactive(List<String> sessionIds) {
        if (!sessionIds.isEmpty()) {
            userSessionRepository.markSessionsInactive(sessionIds);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public long getTotalActiveUsers() {
        return userSessionRepository.getTotalActiveUserCount();
    }

    @Override
    @Transactional(readOnly = true)
    public long getPodActiveUsers(String podId) {
        return userSessionRepository.getActiveUserCountByPod(podId);
    }
}