package com.changelogengine.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

public record AuditJob(UUID jobId, Status status, Instant createdAt,
                       Instant completedAt, AuditResponse result) implements Serializable {
    private static final long serialVersionUID = 1L;
    public enum Status { QUEUED, IN_PROGRESS, COMPLETED, FAILED }
}
