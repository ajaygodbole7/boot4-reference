package com.example.boot4ref.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

/**
 * Enables Spring Retry AOP proxying for @Retryable-annotated service methods.
 *
 * <p>Without @EnableRetry, @Retryable annotations are silently ignored.
 * This configuration activates the retry interceptor that wraps @Retryable
 * methods in a RetryTemplate, catching the declared exceptions and re-invoking
 * the method (in a new @Transactional context) up to maxAttempts times.
 *
 * <p>Required for retry in a new transaction on ObjectOptimisticLockingFailureException.
 */
@Configuration
@EnableRetry
public class RetryConfiguration {
}
