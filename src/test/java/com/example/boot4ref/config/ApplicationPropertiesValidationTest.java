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
    void shouldLoadContextWithValidConfig() {
        contextRunner
                .withPropertyValues("app.max-retries=3")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void shouldFailStartupWhenMaxRetriesIsNegative() {
        contextRunner
                .withPropertyValues("app.max-retries=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailStartupWhenMaxRetriesExceedsMax() {
        contextRunner
                .withPropertyValues("app.max-retries=99")
                .run(context -> assertThat(context).hasFailed());
    }

    @Configuration
    @EnableConfigurationProperties(ApplicationProperties.class)
    static class TestConfig {
    }
}
