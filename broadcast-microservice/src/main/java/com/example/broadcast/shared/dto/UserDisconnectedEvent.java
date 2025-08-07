package com.example.broadcast.shared.dto;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class UserDisconnectedEvent extends ApplicationEvent {
    private final String userId;

    public UserDisconnectedEvent(Object source, String userId) {
        super(source);
        this.userId = userId;
    }
}
