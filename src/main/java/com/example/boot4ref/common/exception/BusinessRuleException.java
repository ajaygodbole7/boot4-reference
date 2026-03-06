package com.example.boot4ref.common.exception;

/**
 * Base exception for business rule violations. Maps to HTTP 422 Unprocessable Entity.
 * Examples: insufficient stock, ordering non-ACTIVE products, duplicate line items.
 */
@ProblemType(slug = "business-rule-violation", title = "Business Rule Violation")
public abstract class BusinessRuleException extends RuntimeException {

    protected BusinessRuleException(String message) {
        super(message);
    }
}
