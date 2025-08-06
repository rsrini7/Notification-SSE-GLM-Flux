package com.example.broadcast.admin.controller;

import com.example.broadcast.admin.dto.DltMessage;
import com.example.broadcast.admin.service.DltService;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.Collection;

@RestController
@RequestMapping("/api/dlt")
@RequiredArgsConstructor
@Slf4j
public class DltAdminController {

    private final DltService dltService;

    @GetMapping("/messages")
    public ResponseEntity<Collection<DltMessage>> getDltMessages() {
        return ResponseEntity.ok(dltService.getDltMessages());
    }

    @PostMapping("/redrive/{id}")
    public ResponseEntity<Void> redriveMessage(@PathVariable String id) {
        try {
            dltService.redriveMessage(id);
            return ResponseEntity.ok().build();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse DLT message payload for redrive: {}", id, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot parse message payload.", e);
        } catch (IllegalArgumentException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
    
    @PostMapping("/redrive-all")
    public ResponseEntity<Void> redriveAllMessages() {
        dltService.redriveAllMessages();
        return ResponseEntity.ok().build();
    }
    
    @DeleteMapping("/purge/{id}")
    public ResponseEntity<Void> purgeMessage(@PathVariable String id) {
        try {
            dltService.purgeMessage(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }
    
    @DeleteMapping("/purge-all")
    public ResponseEntity<Void> purgeAllMessages() {
        dltService.purgeAllMessages();
        return ResponseEntity.noContent().build();
    }
}