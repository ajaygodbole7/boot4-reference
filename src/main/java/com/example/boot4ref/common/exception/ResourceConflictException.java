package com.example.boot4ref.common.exception;

/**
 * Base exception for resource-conflict errors. Maps to HTTP 409.
 * Per-entity exceptions (ProductConflictException, OrderConflictException) extend this.
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }

    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
