package com.example.boot4ref.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ApplicationPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    void shouldLoadContextWithDefaults() {
        contextRunner
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldFailWhenPageSizeIsZero() {
        contextRunner
                .withPropertyValues("app.pagination.default-page-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenPageSizeExceedsMax() {
        contextRunner
                .withPropertyValues("app.pagination.default-page-size=101")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenOutboxBatchSizeIsZero() {
        contextRunner
                .withPropertyValues("app.outbox.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenKafkaTimeoutIsZero() {
        contextRunner
                .withPropertyValues("app.kafka.send-timeout-seconds=0")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ApplicationProperties.class)
    static class TestConfig {
    }
}
