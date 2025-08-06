package com.example.broadcast.shared.exception;

/**
 * Custom unchecked exception to be thrown when the external UserService is unavailable.
 * This allows for specific handling in transactional contexts.
 */
public class UserServiceUnavailableException extends RuntimeException {
    public UserServiceUnavailableException(String message) {
        super(message);
    }
}