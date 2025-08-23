package com.example.broadcast.shared.exception;

import com.example.broadcast.shared.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import java.time.ZonedDateTime;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleResourceNotFoundException(ResourceNotFoundException ex, ServerWebExchange exchange) {
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                HttpStatus.NOT_FOUND.value(),
                "Not Found",
                ex.getMessage(),
                exchange.getRequest().getPath().toString()
        );
        log.warn("ResourceNotFoundException: {}", ex.getMessage());
        return new ResponseEntity<>(errorResponse, HttpStatus.NOT_FOUND);
    }

    @ExceptionHandler(UserServiceUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleUserServiceUnavailableException(UserServiceUnavailableException ex, ServerWebExchange exchange) {
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                HttpStatus.SERVICE_UNAVAILABLE.value(),
                "Service Unavailable",
                ex.getMessage(),
                exchange.getRequest().getPath().toString()
        );
        log.error("UserServiceUnavailableException: {}", ex.getMessage());
        return new ResponseEntity<>(errorResponse, HttpStatus.SERVICE_UNAVAILABLE);
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public ResponseEntity<ErrorResponse> handleValidationExceptions(WebExchangeBindException ex, ServerWebExchange exchange) {
        String errors = ex.getBindingResult()
                .getAllErrors().stream()
                .map(error -> error.getDefaultMessage())
                .collect(Collectors.joining(", "));
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                HttpStatus.BAD_REQUEST.value(),
                "Validation Failed",
                errors,
                exchange.getRequest().getPath().toString()
        );
        log.warn("Validation error: {}", errors);
        return new ResponseEntity<>(errorResponse, HttpStatus.BAD_REQUEST);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatusException(ResponseStatusException ex, ServerWebExchange exchange) {
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                ex.getStatusCode().value(),
                ex.getStatusCode().toString(),
                ex.getReason(),
                exchange.getRequest().getPath().toString()
        );

        // Simplified the log message and removed the incorrect method call.
        if (ex.getStatusCode().is4xxClientError()) {
            log.warn("Client error: {} on path '{}' - Reason: {}", ex.getStatusCode().value(), exchange.getRequest().getPath(), ex.getReason());
        } 
        else if (ex.getStatusCode().is5xxServerError()) {
            log.error("Server error occurred on path {}:", exchange.getRequest().getPath(), ex);
        }

        return new ResponseEntity<>(errorResponse, ex.getStatusCode());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGenericException(Exception ex, ServerWebExchange exchange) {
        ErrorResponse errorResponse = new ErrorResponse(
                ZonedDateTime.now(),
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "Internal Server Error",
                "An unexpected error occurred. Please try again later.",
                exchange.getRequest().getPath().toString()
        );
        log.error("An unexpected error occurred at path {}:", exchange.getRequest().getPath(), ex);
        return new ResponseEntity<>(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR);
    }
}