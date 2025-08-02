package com.example.broadcast.service;

import org.springframework.stereotype.Service;
import java.util.List;

/**
 * A simulated service to represent an external dependency for fetching user data.
 * In a real application, this would make network calls to a user management service.
 * For this project, it is a placeholder and data should be managed in data.sql or external user service.
 */
@Service
public class UserService {

    /**
     * Simulates fetching all user IDs from an external service.
     * @return A list of user IDs.
     */
    public List<String> getAllUserIds() {
        // In a real application, this would make a network call.
        // This could also be a database lookup depending on the architecture.
        // A proper implementation would have a WebClient call here.
        return List.of("user-001", "user-002", "user-003", "user-004", "user-005", "user-006", "user-007", "user-008", "user-009", "user-010");
    }

    /**
     * Simulates fetching user IDs for a specific role from an external service.
     * @param role The role to fetch users for.
     * @return A list of user IDs belonging to the specified role.
     */
    public List<String> getUserIdsByRole(String role) {
        // Mock response for users in a role. In a real app, this would be a network call.
        // This could also be a database lookup depending on the architecture.
        if ("admin".equalsIgnoreCase(role)) {
            return List.of("user-002", "user-003");
        }
        return List.of("user-001", "user-005");
    }
}