package com.example.broadcast.controller;

import com.example.broadcast.dto.DltMessage;
import com.example.broadcast.service.DltService;
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
public class DltController {

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
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage(), e);
        }
    }

    @DeleteMapping("/delete/{id}")
    public ResponseEntity<Void> deleteMessage(@PathVariable String id) {
        dltService.deleteMessage(id);
        return ResponseEntity.noContent().build();
    }
}