package com.example.broadcast.admin.controller;

import com.example.broadcast.admin.service.TestingConfigurationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/testing")
@RequiredArgsConstructor
public class TestingAdminController {


    private final TestingConfigurationService testingConfigService;

    @PostMapping("/kafka-consumer-failure")
    public ResponseEntity<Void> setKafkaConsumerFailure(@RequestBody Map<String, Boolean> request) {
        boolean enabled = request.getOrDefault("enabled", false);
        testingConfigService.setKafkaConsumerFailureEnabled(enabled);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/kafka-consumer-failure")
    public ResponseEntity<Map<String, Boolean>> getKafkaConsumerFailure() {
        boolean isEnabled = testingConfigService.isKafkaConsumerFailureEnabled();
        return ResponseEntity.ok(Map.of("enabled", isEnabled));
    }
}