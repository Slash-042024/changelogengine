package com.changelogengine.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.dao.QueryTimeoutException;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AuditJobRecoveryTests {
    @Test
    void retriesTransientRedisFailuresUsingTheOriginalStartupCutoff() {
        var jobs = mock(AuditJobRepository.class);
        when(jobs.failInterruptedJobs(any()))
                .thenThrow(new RedisConnectionFailureException("Redis starting"))
                .thenThrow(new QueryTimeoutException("Redis warming up"))
                .thenReturn(2);
        new AuditJobRecovery(jobs).afterSingletonsInstantiated();
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(jobs, times(3)).failInterruptedJobs(cutoff.capture());
        assertEquals(1, cutoff.getAllValues().stream().distinct().count());
    }

    @Test
    void persistentRedisFailureStillFailsStartupAfterFiveAttempts() {
        var jobs = mock(AuditJobRepository.class);
        var failure = new RedisConnectionFailureException("Redis unavailable");
        when(jobs.failInterruptedJobs(any())).thenThrow(failure);
        assertSame(failure, assertThrows(RedisConnectionFailureException.class,
                () -> new AuditJobRecovery(jobs).afterSingletonsInstantiated()));
        verify(jobs, times(5)).failInterruptedJobs(any());
    }

    @Test
    void doesNotRetryUnrelatedRecoveryErrors() {
        var jobs = mock(AuditJobRepository.class);
        when(jobs.failInterruptedJobs(any())).thenThrow(new IllegalStateException("Corrupt job"));
        assertThrows(IllegalStateException.class,
                () -> new AuditJobRecovery(jobs).afterSingletonsInstantiated());
        verify(jobs).failInterruptedJobs(any());
    }

    @Test
    void shutdownInterruptStopsRetryingAndPreservesInterruptFlag() {
        var jobs = mock(AuditJobRepository.class);
        when(jobs.failInterruptedJobs(any())).thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        try {
            Thread.currentThread().interrupt();
            assertThrows(IllegalStateException.class,
                    () -> new AuditJobRecovery(jobs).afterSingletonsInstantiated());
            assertTrue(Thread.currentThread().isInterrupted());
            verify(jobs).failInterruptedJobs(any());
        } finally {
            Thread.interrupted();
        }
    }
}
