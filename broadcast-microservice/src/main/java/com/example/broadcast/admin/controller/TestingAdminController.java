package com.example.broadcast.admin.controller;

import com.example.broadcast.admin.service.TestingConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/testing")
@RequiredArgsConstructor
public class TestingAdminController {

    private final TestingConfigurationService testingConfigService;

    @PostMapping("/kafka-consumer-failure")
    public ResponseEntity<Void> setKafkaConsumerFailure(@RequestBody Map<String, Boolean> request) {
        boolean enabled = request.getOrDefault("enabled", false);
        // Use the new arming method
        testingConfigService.setArm(enabled);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/kafka-consumer-failure")
    public ResponseEntity<Map<String, Boolean>> getKafkaConsumerFailure() {
        // Return the current armed state
        boolean isEnabled = testingConfigService.isArmed();
        return ResponseEntity.ok(Map.of("enabled", isEnabled));
    }
}