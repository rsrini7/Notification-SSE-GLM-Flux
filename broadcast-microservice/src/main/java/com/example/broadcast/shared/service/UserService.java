package com.example.broadcast.shared.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;

@Service
public class UserService {
    
    // **Simulated user-to-role mapping**
    private static final Map<String, List<String>> userRoles = Map.of(
        "user-001", List.of("USER"),
        "user-002", List.of("USER", "ADMIN"),
        "user-003", List.of("USER", "ADMIN"),
        "user-004", List.of("USER"),
        "user-005", List.of("USER", "MODERATOR")
    );

    public List<String> getAllUserIds() {
        return java.util.stream.IntStream.rangeClosed(1, 120)
                .mapToObj(i -> String.format("user-%03d", i))
                .collect(java.util.stream.Collectors.toList());
    }

    public List<String> getUserIdsByRole(String role) {
        if ("ADMIN".equalsIgnoreCase(role)) {
            return List.of("user-002", "user-003");
        }
        if ("MODERATOR".equalsIgnoreCase(role)) {
            return List.of("user-005");
        }
        // Default to all users for a generic "USER" role
        return getAllUserIds();
    }
    
    // **NEW METHOD**
    public List<String> getRolesForUser(String userId) {
        return userRoles.getOrDefault(userId, List.of("USER"));
    }
}