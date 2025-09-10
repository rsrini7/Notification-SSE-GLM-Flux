// Create a new file:
// broadcast-microservice/broadcast-user-service/src/main/java/com/example/broadcast/user/controller/CacheAdminController.java

package com.example.broadcast.user.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.geode.cache.Region;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import com.example.broadcast.shared.dto.cache.UserMessageInbox;

@RestController
@RequestMapping("/api/admin/cache") // An admin-style path on the user-service
@RequiredArgsConstructor
@Slf4j
public class CacheAdminController {

    private final Region<String, List<UserMessageInbox>> userMessagesInboxRegion;

    /**
     * Manually clears all entries from the user-messages-inbox region across the cluster.
     * This is an administrative action and should be used with caution.
     */
    @DeleteMapping("/inbox/all")
    public ResponseEntity<Map<String, Object>> clearUserInboxRegion() {
        log.warn("Received administrative request to clear the entire 'user-messages-inbox' region.");
        int sizeBefore = userMessagesInboxRegion.size();
        
        // The clear() operation will remove all entries from the region on the server
        userMessagesInboxRegion.clear();
        
        int sizeAfter = userMessagesInboxRegion.size();
        log.info("Successfully cleared 'user-messages-inbox' region. Size before: {}, Size after: {}.", sizeBefore, sizeAfter);

        Map<String, Object> response = Map.of(
            "message", "Region 'user-messages-inbox' has been cleared.",
            "entriesRemoved", sizeBefore
        );
        return ResponseEntity.ok(response);
    }
}