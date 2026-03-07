package com.example.boot4ref.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "app")
@Validated
public class ApplicationProperties {

    @NotNull
    private String errorBaseUrl = "https://api.boot4ref.example.com/errors/";

    @Valid
    @NotNull
    private Pagination pagination = new Pagination();

    @Valid
    @NotNull
    private Outbox outbox = new Outbox();

    @NotNull
    @Min(1)
    private Integer retryAfterSeconds = 30;

    @Valid
    @NotNull
    private Kafka kafka = new Kafka();

    public String getErrorBaseUrl() { return errorBaseUrl; }
    public void setErrorBaseUrl(String errorBaseUrl) { this.errorBaseUrl = errorBaseUrl; }

    public Pagination getPagination() { return pagination; }
    public void setPagination(Pagination pagination) { this.pagination = pagination; }

    public Outbox getOutbox() { return outbox; }
    public void setOutbox(Outbox outbox) { this.outbox = outbox; }

    public Integer getRetryAfterSeconds() { return retryAfterSeconds; }
    public void setRetryAfterSeconds(Integer retryAfterSeconds) { this.retryAfterSeconds = retryAfterSeconds; }

    public Kafka getKafka() { return kafka; }
    public void setKafka(Kafka kafka) { this.kafka = kafka; }

    public static class Pagination {
        @NotNull
        @Min(1) @Max(100)
        private Integer defaultPageSize = 20;

        @NotNull
        @Min(1) @Max(1000)
        private Integer maxPageSize = 100;

        public Integer getDefaultPageSize() { return defaultPageSize; }
        public void setDefaultPageSize(Integer defaultPageSize) { this.defaultPageSize = defaultPageSize; }

        public Integer getMaxPageSize() { return maxPageSize; }
        public void setMaxPageSize(Integer maxPageSize) { this.maxPageSize = maxPageSize; }
    }

    public static class Outbox {
        @NotNull
        @Min(1) @Max(100)
        private Integer batchSize = 5;

        @NotNull
        @Min(1)
        private Integer retentionDays = 7;

        @NotNull
        @Min(1) @Max(50)
        private Integer maxRetryCount = 5;

        @NotNull
        @Min(1)
        private Long pollIntervalMs = 1000L;

        @NotNull
        @Min(1)
        private Long cleanupIntervalMs = 3_600_000L;

        public Integer getBatchSize() { return batchSize; }
        public void setBatchSize(Integer batchSize) { this.batchSize = batchSize; }

        public Integer getRetentionDays() { return retentionDays; }
        public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }

        public Integer getMaxRetryCount() { return maxRetryCount; }
        public void setMaxRetryCount(Integer maxRetryCount) { this.maxRetryCount = maxRetryCount; }

        public Long getPollIntervalMs() { return pollIntervalMs; }
        public void setPollIntervalMs(Long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

        public Long getCleanupIntervalMs() { return cleanupIntervalMs; }
        public void setCleanupIntervalMs(Long cleanupIntervalMs) { this.cleanupIntervalMs = cleanupIntervalMs; }
    }

    public static class Kafka {
        @NotNull
        @Min(1)
        private Long sendTimeoutSeconds = 5L;

        public Long getSendTimeoutSeconds() { return sendTimeoutSeconds; }
        public void setSendTimeoutSeconds(Long sendTimeoutSeconds) { this.sendTimeoutSeconds = sendTimeoutSeconds; }
    }
}
