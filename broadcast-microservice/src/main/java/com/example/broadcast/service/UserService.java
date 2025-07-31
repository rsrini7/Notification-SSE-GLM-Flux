// broadcast-microservice/src/main/java/com/example/broadcast/service/UserService.java
package com.example.broadcast.service;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import com.example.broadcast.exception.UserServiceUnavailableException;

/**
 * A simulated service to represent an external dependency for fetching user data.
 * This service is designed to fail intermittently to test resilience patterns.
 */
@Service
public class UserService {

    /**
     * Simulates fetching all user IDs from an external service.
     * This method has a 50% chance of throwing a UserServiceUnavailableException to simulate a service failure.
     * @return A list of user IDs.
     */
    public List<String> getAllUserIds() {
        if (ThreadLocalRandom.current().nextInt(10) < 5) {
            throw new UserServiceUnavailableException("External User Service is unavailable");
        }
        // In a real application, this would make a network call to a user management service.
        return List.of("user-001", "user-002", "user-003", "user-004", "user-005", "user-006", "user-007", "user-008", "user-009", "user-010");
    }

    /**
     * Simulates fetching user IDs for a specific role from an external service.
     * This method also has a 50% chance of failure.
     * @param role The role to fetch users for.
     * @return A list of user IDs belonging to the specified role.
     */
    public List<String> getUserIdsByRole(String role) {
         if (ThreadLocalRandom.current().nextInt(10) < 5) {
            throw new UserServiceUnavailableException("External User Service is unavailable for role: " + role);
        }
        // Mock response for users in a role.
        return List.of("user-001", "user-003", "user-005");
    }
}
