package com.changelogengine.service;

import com.changelogengine.dto.AuditJob;
import com.changelogengine.dto.AuditResponse;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ConcurrentModificationException;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;

@Repository
public class AuditJobRepository {
    private static final Duration JOB_TTL = Duration.ofHours(24);
    private static final String KEY_PREFIX = "AuditJob:";
    private final RedisTemplate<String, AuditJob> redis;
    private enum UpdateOutcome { UPDATED, UNCHANGED, RETRY }

    public AuditJobRepository(RedisTemplate<String, AuditJob> redis) {
        this.redis = redis;
    }

    public AuditJob create() {
        AuditJob job = new AuditJob(UUID.randomUUID(), AuditJob.Status.QUEUED, Instant.now(), null, null);
        redis.opsForValue().set(KEY_PREFIX + job.jobId(), job, JOB_TTL);
        return job;
    }

    public Optional<AuditJob> findById(UUID jobId) {
        return Optional.ofNullable(redis.opsForValue().get(KEY_PREFIX + jobId));
    }

    public void markInProgress(UUID jobId) {
        update(jobId, job -> job.status() == AuditJob.Status.QUEUED
                ? new AuditJob(jobId, AuditJob.Status.IN_PROGRESS, job.createdAt(), null, null) : job);
    }

    public void complete(UUID jobId, AuditResponse result) {
        finish(jobId, AuditJob.Status.COMPLETED, result);
    }

    public void fail(UUID jobId, AuditResponse result) {
        finish(jobId, AuditJob.Status.FAILED, result);
    }

    public int failInterruptedJobs(Instant startupTime) {
        int recovered = 0;
        // SCAN avoids blocking Redis with a full keyspace KEYS command.
        try (var keys = redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(100).build())) {
            while (keys.hasNext()) {
                AuditJob job = redis.opsForValue().get(keys.next());
                if (job != null && wasInterrupted(job, startupTime)) {
                    boolean changed = update(job.jobId(), current -> wasInterrupted(current, startupTime)
                            ? new AuditJob(current.jobId(), AuditJob.Status.FAILED, current.createdAt(),
                                    Instant.now(), new AuditResponse("ERROR: Job interrupted by application restart",
                                            0, 0, List.of()))
                            : current, true);
                    if (changed) {
                        recovered++;
                    }
                }
            }
        }
        return recovered;
    }

    private boolean wasInterrupted(AuditJob job, Instant startupTime) {
        return !job.createdAt().isAfter(startupTime)
                && (job.status() == AuditJob.Status.IN_PROGRESS || job.status() == AuditJob.Status.QUEUED);
    }

    private void finish(UUID jobId, AuditJob.Status status, AuditResponse result) {
        update(jobId, job ->
                job.status() == AuditJob.Status.COMPLETED || job.status() == AuditJob.Status.FAILED
                        ? job : new AuditJob(jobId, status, job.createdAt(), Instant.now(), result));
    }

    private boolean update(UUID jobId, UnaryOperator<AuditJob> transition) {
        return update(jobId, transition, false);
    }

    private boolean update(UUID jobId, UnaryOperator<AuditJob> transition, boolean preserveTtl) {
        String key = KEY_PREFIX + jobId;
        // WATCH keeps transitions atomic across workers and server instances.
        for (int attempt = 0; attempt < 10; attempt++) {
            UpdateOutcome outcome = redis.execute(new SessionCallback<UpdateOutcome>() {
                @Override
                @SuppressWarnings("unchecked")
                public <K, V> UpdateOutcome execute(RedisOperations<K, V> operations) {
                    RedisOperations<String, AuditJob> jobs = (RedisOperations<String, AuditJob>) operations;
                    jobs.watch(key);
                    AuditJob job = jobs.opsForValue().get(key);
                    if (job == null) {
                        jobs.unwatch();
                        return UpdateOutcome.UNCHANGED;
                    }
                    AuditJob next = transition.apply(job);
                    if (next == job) {
                        jobs.unwatch();
                        return UpdateOutcome.UNCHANGED;
                    }
                    jobs.multi();
                    if (preserveTtl) {
                        jobs.opsForValue().set(key, next, Expiration.keepTtl());
                    } else {
                        // Normal processing retains the existing 24-hour retention policy.
                        jobs.opsForValue().set(key, next, JOB_TTL);
                    }
                    var results = jobs.exec();
                    return results != null && !results.isEmpty() ? UpdateOutcome.UPDATED : UpdateOutcome.RETRY;
                }
            });
            if (outcome == UpdateOutcome.UPDATED || outcome == UpdateOutcome.UNCHANGED) {
                return outcome == UpdateOutcome.UPDATED;
            }
        }
        throw new ConcurrentModificationException("Could not update audit job " + jobId);
    }
}
