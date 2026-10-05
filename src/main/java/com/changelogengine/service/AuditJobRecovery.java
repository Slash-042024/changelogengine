package com.changelogengine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;

/** Startup recovery for a single backend instance sharing this Redis job namespace. */
@Component
public class AuditJobRecovery implements SmartInitializingSingleton {
    private static final Logger log = LoggerFactory.getLogger(AuditJobRecovery.class);
    private static final int MAX_ATTEMPTS = 5;
    private final AuditJobRepository jobs;
    private final Instant startupTime = Instant.now();

    public AuditJobRecovery(AuditJobRepository jobs) {
        this.jobs = jobs;
    }

    @Override
    public void afterSingletonsInstantiated() {
        // Runs during context initialization, before Netty starts accepting requests.
        // Brief Redis outages are retried; persistent outages still fail startup.
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                int recovered = jobs.failInterruptedJobs(startupTime);
                log.info("Marked {} interrupted audit jobs as FAILED", recovered);
                return;
            } catch (RedisConnectionFailureException | QueryTimeoutException e) {
                if (attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("Redis unavailable during audit recovery (attempt {}/{}); retrying in 1 second: {}",
                        attempt, MAX_ATTEMPTS, e.getMessage());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Audit startup recovery interrupted", interrupted);
                }
            }
        }
    }
}
