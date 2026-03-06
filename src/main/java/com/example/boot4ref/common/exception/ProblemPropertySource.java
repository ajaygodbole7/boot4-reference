package com.example.boot4ref.common.exception;

import java.util.Map;

/**
 * Opt-in interface for exceptions that carry structured data for RFC 9457 responses.
 * The handler calls {@link #problemProperties()} and adds each entry to
 * {@code ProblemDetail.setProperty()}.
 *
 * <p>No Spring dependency. No reflection. The exception author controls exactly
 * what appears in the response via a {@code Map.of()} return.
 */
public interface ProblemPropertySource {
    Map<String, Object> problemProperties();
}
