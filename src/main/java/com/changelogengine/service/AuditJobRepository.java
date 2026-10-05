package com.changelogengine.service;

import com.changelogengine.dto.AuditJob;
import com.changelogengine.dto.AuditResponse;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class AuditJobRepository {
    private final ConcurrentHashMap<UUID, AuditJob> jobs = new ConcurrentHashMap<>();

    public AuditJob create() {
        AuditJob job = new AuditJob(UUID.randomUUID(), AuditJob.Status.QUEUED, Instant.now(), null, null);
        jobs.put(job.jobId(), job);
        return job;
    }

    public Optional<AuditJob> findById(UUID jobId) {
        return Optional.ofNullable(jobs.get(jobId));
    }

    public void markInProgress(UUID jobId) {
        jobs.computeIfPresent(jobId, (id, job) -> job.status() == AuditJob.Status.QUEUED
                ? new AuditJob(id, AuditJob.Status.IN_PROGRESS, job.createdAt(), null, null) : job);
    }

    public void complete(UUID jobId, AuditResponse result) {
        finish(jobId, AuditJob.Status.COMPLETED, result);
    }

    public void fail(UUID jobId, AuditResponse result) {
        finish(jobId, AuditJob.Status.FAILED, result);
    }

    private void finish(UUID jobId, AuditJob.Status status, AuditResponse result) {
        jobs.computeIfPresent(jobId, (id, job) ->
                job.status() == AuditJob.Status.COMPLETED || job.status() == AuditJob.Status.FAILED
                        ? job : new AuditJob(id, status, job.createdAt(), Instant.now(), result));
    }
}
