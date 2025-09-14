package com.example.broadcast.shared.service;

import org.springframework.stereotype.Service;

import com.example.broadcast.shared.aspect.Monitored;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
@Monitored("service")
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
        return java.util.stream.IntStream.rangeClosed(1, 10)
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

    /**
     * Simulates fetching a list of user IDs for a given product.
     * In a real system, this would query a database or another microservice.
     * @param productId The name of the product (e.g., "Payments").
     * @return A list of user IDs subscribed to that product.
     */
    public List<String> getUserIdsByProduct(String productId) {
        log.info("Simulating fetch for users of product: {}", productId);
        // This is a simple mock. A real implementation would be more complex.
        if ("Payments".equalsIgnoreCase(productId)) {
            // Return a specific subset of users for the "Payments" product
            return List.of("user-001", "user-003", "user-005", "user-010");
        }
        if ("Analytics".equalsIgnoreCase(productId)) {
            // Return another subset for "Analytics"
            return List.of("user-003", "user-007", "user-009");
        }
        // Return an empty list for unknown products
        return List.of();
    }
}