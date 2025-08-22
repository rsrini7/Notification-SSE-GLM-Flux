package com.example.broadcast.admin.controller;

// import com.example.broadcast.shared.service.TestingConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // ADD THIS IMPORT
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/testing")
@RequiredArgsConstructor
@Slf4j
public class TestingAdminController {

    // private final TestingConfigurationService testingConfigurationService;

    @PostMapping("/kafka-consumer-failure")
    public ResponseEntity<Void> setKafkaConsumerFailure(@RequestBody Map<String, Boolean> request) {
        // boolean enabled = request.getOrDefault("enabled", false);
        // log.debug("Received request to set DLT Test Mode. Enabled: {}", enabled);
        // testingConfigurationService.setArm(enabled);
        return ResponseEntity.internalServerError().build();
    }

    @GetMapping("/kafka-consumer-failure")
    public ResponseEntity<Map<String, Boolean>> getKafkaConsumerFailure() {
        // boolean isEnabled = testingConfigurationService.isArmed();
        // log.debug("Received request to get DLT Test Mode status. Currently Enabled: {}", isEnabled);
        return ResponseEntity.ok(Map.of("enabled", false));
    }
}