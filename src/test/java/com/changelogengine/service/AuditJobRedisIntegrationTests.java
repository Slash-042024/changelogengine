package com.changelogengine.service;

import com.changelogengine.config.RedisConfig;
import com.changelogengine.ChangelogEngineApplication;
import com.changelogengine.dto.AuditJob;
import com.changelogengine.dto.AuditResponse;
import com.changelogengine.dto.BreakingChangeDto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in live Redis checks in a separate database; never clears the Redis database. */
@EnabledIfSystemProperty(named = "audit.redis.integration", matches = "true")
class AuditJobRedisIntegrationTests {
    private static LettuceConnectionFactory connectionFactory;
    private static RedisTemplate<String, AuditJob> redis;
    private final List<UUID> testJobs = new ArrayList<>();

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(
                System.getProperty("audit.redis.host", "localhost"),
                Integer.getInteger("audit.redis.port", 6379));
        connectionFactory.setDatabase(Integer.getInteger("audit.redis.database", 15));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new RedisConfig().auditJobRedisTemplate(connectionFactory);
        redis.afterPropertiesSet();
        try (var connection = connectionFactory.getConnection()) {
            assertEquals("PONG", connection.ping());
        }
    }

    @AfterAll
    static void disconnect() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @AfterEach
    void removeTestJobs() {
        for (UUID id : testJobs) {
            redis.delete(key(id));
        }
    }

    @Test
    void savesNewJobAndRetrievesItByIdWith24HourTtl() {
        var jobs = new AuditJobRepository(redis);
        var job = create(jobs);
        assertEquals(AuditJob.Status.QUEUED, job.status());
        assertEquals(job, jobs.findById(job.jobId()).orElseThrow());
        assertNull(job.result());
        assert24HourTtl(job.jobId());
        assertTrue(jobs.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void updatesJobStatusAndRenewsTtl() {
        var jobs = new AuditJobRepository(redis);
        var job = create(jobs);
        redis.expire(key(job.jobId()), Duration.ofSeconds(30));
        jobs.markInProgress(job.jobId());
        assertEquals(AuditJob.Status.IN_PROGRESS, jobs.findById(job.jobId()).orElseThrow().status());
        assert24HourTtl(job.jobId());
    }

    @Test
    void finalResponseSurvivesNewRepositoryAndRedisConnection() {
        var jobs = new AuditJobRepository(redis);
        var job = create(jobs);
        jobs.markInProgress(job.jobId());
        var result = new AuditResponse("SUCCESS", 2, 1, List.of(
                new BreakingChangeDto("Example", "run()", "Removed", "HIGH")));
        jobs.complete(job.jobId(), result);
        // A fresh application context creates a fresh template against the same Redis state.
        var template = new RedisConfig().auditJobRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        var reloaded = new AuditJobRepository(template).findById(job.jobId()).orElseThrow();
        assertEquals(AuditJob.Status.COMPLETED, reloaded.status());
        assertEquals(result, reloaded.result());
        assertNotNull(reloaded.completedAt());
        assert24HourTtl(job.jobId());
    }

    @Test
    void redisExpiresRecordsAndUpdatesDoNotResurrectThem() throws Exception {
        var jobs = new AuditJobRepository(redis);
        var job = create(jobs);
        assert24HourTtl(job.jobId());
        // Shorten only this test record's expiry rather than waiting 24 hours.
        redis.expire(key(job.jobId()), Duration.ofMillis(100));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jobs.findById(job.jobId()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(jobs.findById(job.jobId()).isEmpty());
        assertEquals(-2L, redis.getExpire(key(job.jobId())));
        jobs.fail(job.jobId(), new AuditResponse("ERROR", 0, 0, List.of()));
        assertTrue(jobs.findById(job.jobId()).isEmpty());
    }

    @Test
    void actualApplicationRestartRecoversUnfinishedJobsWithoutChangingTerminalJobsOrExpiry() {
        Map<UUID, Long> previousTtls = new HashMap<>();
        AuditJob completedSnapshot;
        AuditJob failedSnapshot;
        UUID runningId;
        UUID queuedId;
        try (var firstRun = startApplication()) {
            var jobs = firstRun.getBean(AuditJobRepository.class);
            var running = create(jobs);
            runningId = running.jobId();
            jobs.markInProgress(runningId);
            queuedId = create(jobs).jobId();
            var completed = create(jobs);
            var result = new AuditResponse("SUCCESS", 2, 1, List.of(
                    new BreakingChangeDto("Example", "run()", "Removed", "HIGH")));
            jobs.complete(completed.jobId(), result);
            completedSnapshot = jobs.findById(completed.jobId()).orElseThrow();
            var failed = create(jobs);
            jobs.fail(failed.jobId(), new AuditResponse("ERROR: Original failure", 1, 0, List.of()));
            failedSnapshot = jobs.findById(failed.jobId()).orElseThrow();
            for (UUID id : testJobs) {
                redis.expire(key(id), Duration.ofSeconds(60));
                previousTtls.put(id, redis.getExpire(key(id), TimeUnit.MILLISECONDS));
            }
        }
        try (var secondRun = startApplication()) {
            // The first HTTP requests after startup see the recovered states.
            for (UUID id : List.of(runningId, queuedId)) {
                var recovered = status(secondRun, id);
                assertEquals(AuditJob.Status.FAILED, recovered.status());
                assertEquals("ERROR: Job interrupted by application restart", recovered.result().status());
                assertNotNull(recovered.completedAt());
            }
            assertEquals(completedSnapshot, status(secondRun, completedSnapshot.jobId()));
            assertEquals(failedSnapshot, status(secondRun, failedSnapshot.jobId()));
            for (UUID id : testJobs) {
                Long ttl = redis.getExpire(key(id), TimeUnit.MILLISECONDS);
                assertNotNull(ttl);
                assertTrue(ttl > 0 && ttl <= previousTtls.get(id), "Restart must not extend TTL for " + id);
            }
        }
    }

    @Test
    void recoveredJobExpiresOnItsOriginalDeadline() throws Exception {
        var jobs = new AuditJobRepository(redis);
        var job = create(jobs);
        jobs.markInProgress(job.jobId());
        redis.expire(key(job.jobId()), Duration.ofSeconds(1));
        jobs.failInterruptedJobs(Instant.now());
        assertEquals(AuditJob.Status.FAILED, jobs.findById(job.jobId()).orElseThrow().status());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (jobs.findById(job.jobId()).isPresent() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(jobs.findById(job.jobId()).isEmpty(), "Recovery must not renew an expiring job");
    }

    private ConfigurableApplicationContext startApplication() {
        return new SpringApplicationBuilder(ChangelogEngineApplication.class).run(
                "--server.port=0",
                "--spring.data.redis.host=" + System.getProperty("audit.redis.host", "localhost"),
                "--spring.data.redis.port=" + Integer.getInteger("audit.redis.port", 6379),
                "--spring.data.redis.database=" + Integer.getInteger("audit.redis.database", 15));
    }

    private AuditJob status(ConfigurableApplicationContext context, UUID id) {
        String port = context.getEnvironment().getProperty("local.server.port");
        assertNotNull(port);
        AuditJob job = WebClient.create("http://localhost:" + port).get()
                .uri("/api/v1/audit/status/{jobId}", id)
                .retrieve().bodyToMono(AuditJob.class).block(Duration.ofSeconds(5));
        assertNotNull(job);
        return job;
    }

    private AuditJob create(AuditJobRepository jobs) {
        var job = jobs.create();
        testJobs.add(job.jobId());
        return job;
    }

    private void assert24HourTtl(UUID id) {
        Long ttl = redis.getExpire(key(id));
        assertNotNull(ttl);
        assertTrue(ttl > 86390 && ttl <= 86400, "TTL should be 24 hours, got " + ttl);
    }

    private String key(UUID id) {
        return "AuditJob:" + id;
    }
}
