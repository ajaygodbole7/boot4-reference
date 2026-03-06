package com.example.boot4ref.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/**
 * Wires the Spring-managed {@link OpenTelemetry} instance into the Logback
 * {@link OpenTelemetryAppender}. The appender buffers log events until this
 * install call, then replays them through the OTel SDK log pipeline.
 */
@Configuration(proxyBeanMethods = false)
class OpenTelemetryLoggingConfiguration {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryLoggingConfiguration(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @EventListener(ApplicationReadyEvent.class)
    void installOpenTelemetryOnLogbackAppender() {
        OpenTelemetryAppender.install(openTelemetry);
    }
}
