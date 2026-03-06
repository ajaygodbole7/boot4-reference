package com.example.boot4ref.common.exception;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the RFC 9457 Problem Detail mapping for a domain exception.
 * The handler reads this annotation to produce slug-based type URIs,
 * stable errorCode values, and specific titles.
 *
 * <p>Applied to exception classes. If absent, the handler falls back to
 * hardcoded defaults in the handler method. {@code @Inherited} ensures
 * subclasses without their own annotation inherit from the base class.
 *
 * <p>HTTP status is NOT part of the annotation — the handler method
 * determines the status. This prevents mismatches where an annotation
 * could silently override the handler's intended status code.
 */
@Inherited
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProblemType {
    String slug();
    String title();
}
