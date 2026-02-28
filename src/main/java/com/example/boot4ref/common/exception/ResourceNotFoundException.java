package com.example.boot4ref.common.exception;

/**
 * Base exception for resource-not-found errors. Maps to HTTP 404.
 * Per-entity exceptions (ProductNotFoundException, OrderNotFoundException) extend this.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
