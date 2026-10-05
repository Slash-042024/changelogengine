package com.changelogengine.service;

import com.changelogengine.config.RedisConfig;
import com.changelogengine.dto.AuditJob;
import com.changelogengine.dto.AuditResponse;
import com.changelogengine.dto.BreakingChangeDto;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuditJobRepositoryTests {
    @Test
    void startupRecoveryFailsUnfinishedJobsAndPreservesTerminalJobs() {
        var store = new RedisTestStore();
        var jobs = new AuditJobRepository(store.redis);
        var queued = jobs.create();
        var running = jobs.create();
        jobs.markInProgress(running.jobId());
        var completed = jobs.create();
        var result = new AuditResponse("SUCCESS", 1, 0, List.of());
        jobs.complete(completed.jobId(), result);
        var failed = jobs.create();
        jobs.fail(failed.jobId(), new AuditResponse("ERROR: Previous failure", 0, 0, List.of()));
        var terminalFailure = jobs.findById(failed.jobId()).orElseThrow();
        var startupTime = java.time.Instant.now();
        store.expirations.put("AuditJob:" + queued.jobId(), Duration.ofSeconds(30));
        store.expirations.put("AuditJob:" + running.jobId(), Duration.ofSeconds(30));
        var newer = new AuditJob(UUID.randomUUID(), AuditJob.Status.QUEUED,
                startupTime.plusSeconds(60), null, null);
        store.operations.set("AuditJob:" + newer.jobId(), newer, Duration.ofHours(24));

        assertEquals(2, jobs.failInterruptedJobs(startupTime));
        for (var id : List.of(queued.jobId(), running.jobId())) {
            var job = jobs.findById(id).orElseThrow();
            assertEquals(AuditJob.Status.FAILED, job.status());
            assertNotNull(job.completedAt());
            assertTrue(job.result().status().contains("interrupted"));
            assertEquals(Duration.ofSeconds(30), store.expirations.get("AuditJob:" + id));
        }
        assertEquals(result, jobs.findById(completed.jobId()).orElseThrow().result());
        assertEquals(terminalFailure, jobs.findById(failed.jobId()).orElseThrow());
        assertEquals(newer, jobs.findById(newer.jobId()).orElseThrow());
        assertEquals(0, jobs.failInterruptedJobs(startupTime));
    }

    @Test
    void recoveryRunsDuringSingletonInitialization() {
        var jobs = mock(AuditJobRepository.class);
        new AuditJobRecovery(jobs).afterSingletonsInstantiated();
        verify(jobs).failInterruptedJobs(any(java.time.Instant.class));
    }

    @Test
    void newRepositoryReadsSerializedJobWithNestedResultAndExpiry() {
        var store = new RedisTestStore();
        var jobs = new AuditJobRepository(store.redis);
        var queued = jobs.create();
        String key = "AuditJob:" + queued.jobId();
        assertEquals(Duration.ofHours(24), store.expirations.get(key));
        jobs.markInProgress(queued.jobId());
        var response = new AuditResponse("SUCCESS", 2, 1, List.of(
                new BreakingChangeDto("Example", "run()", "Removed", "HIGH")));
        jobs.complete(queued.jobId(), response);
        var reloaded = new AuditJobRepository(store.redis).findById(queued.jobId()).orElseThrow();
        assertEquals(AuditJob.Status.COMPLETED, reloaded.status());
        assertEquals(queued.createdAt(), reloaded.createdAt());
        assertNotNull(reloaded.completedAt());
        assertEquals(response, reloaded.result());
        assertEquals(Duration.ofHours(24), store.expirations.get(key));
        verify(store.operations, times(3)).set(eq(key), any(AuditJob.class), eq(Duration.ofHours(24)));
        // Terminal jobs cannot regress or have expiry extended by repeated updates.
        jobs.fail(queued.jobId(), new AuditResponse("ERROR", 0, 0, List.of()));
        jobs.markInProgress(queued.jobId());
        assertEquals(reloaded, jobs.findById(queued.jobId()).orElseThrow());
        verify(store.operations, times(3)).set(eq(key), any(AuditJob.class), any(Duration.class));
    }

    @Test
    void failureHasExpiryAndMissingJobIsNotRecreated() {
        var store = new RedisTestStore();
        var jobs = new AuditJobRepository(store.redis);
        var job = jobs.create();
        jobs.fail(job.jobId(), new AuditResponse("ERROR", 0, 0, List.of()));
        assertEquals(AuditJob.Status.FAILED, jobs.findById(job.jobId()).orElseThrow().status());
        assertEquals(Duration.ofHours(24), store.expirations.get("AuditJob:" + job.jobId()));
        UUID missing = UUID.randomUUID();
        jobs.markInProgress(missing);
        assertTrue(jobs.findById(missing).isEmpty());
        assertFalse(store.values.containsKey("AuditJob:" + missing));
    }

    @Test
    void abortedRedisTransactionIsRetried() {
        var store = new RedisTestStore();
        var jobs = new AuditJobRepository(store.redis);
        var job = jobs.create();
        String key = "AuditJob:" + job.jobId();
        when(store.operations.get(key)).thenReturn(job);
        when(store.redis.exec()).thenReturn(null).thenReturn(List.of(true));
        jobs.markInProgress(job.jobId());
        verify(store.redis, times(2)).watch(key);
        verify(store.redis, times(2)).exec();
    }

    @Test
    void configuredSerializerRoundTripsEntireJob() {
        var template = new RedisConfig().auditJobRedisTemplate(mock(RedisConnectionFactory.class));
        assertInstanceOf(StringRedisSerializer.class, template.getKeySerializer());
        var original = new AuditJob(UUID.randomUUID(), AuditJob.Status.COMPLETED,
                java.time.Instant.now(), java.time.Instant.now(),
                new AuditResponse("SUCCESS", 1, 1, List.of(
                        new BreakingChangeDto("Example", "run()", "Removed", "HIGH"))));
        @SuppressWarnings("unchecked")
        var serializer = (org.springframework.data.redis.serializer.RedisSerializer<Object>) template.getValueSerializer();
        assertEquals(original, serializer.deserialize(serializer.serialize(original)));
    }
}
