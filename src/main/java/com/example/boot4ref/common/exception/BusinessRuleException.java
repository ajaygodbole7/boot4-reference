package com.example.boot4ref.common.exception;

/**
 * Base exception for business rule violations. Maps to HTTP 422 Unprocessable Entity.
 * Examples: insufficient stock, ordering discontinued products.
 */
public abstract class BusinessRuleException extends RuntimeException {

    protected BusinessRuleException(String message) {
        super(message);
    }
}
