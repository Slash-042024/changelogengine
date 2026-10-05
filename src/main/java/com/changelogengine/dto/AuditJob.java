package com.changelogengine.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditJob(UUID jobId, Status status, Instant createdAt,
                       Instant completedAt, AuditResponse result) {
    public enum Status { QUEUED, IN_PROGRESS, COMPLETED, FAILED }
}
