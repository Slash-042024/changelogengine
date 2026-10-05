package com.changelogengine.service;

import com.changelogengine.config.AsyncConfig;
import com.changelogengine.controller.AuditController;
import com.changelogengine.dto.AuditJob;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.codec.multipart.FilePart;
import reactor.core.publisher.Flux;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AsyncAuditServiceTests {
    @Test
    void submissionReturnsWhileWorkerIsBusyAndEventuallyCompletes() throws Exception {
        PomParserService parser = mock(PomParserService.class);
        MavenCentralClient client = mock(MavenCentralClient.class);
        AstDiffEngine engine = mock(AstDiffEngine.class);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> thread = new AtomicReference<>();
        AtomicReference<InputStream> stream = new AtomicReference<>();
        var currentJar = Files.createTempFile("audit-test-current-", ".jar");
        var targetJar = Files.createTempFile("audit-test-target-", ".jar");
        when(parser.parseDependencies(any())).thenAnswer(invocation -> {
            thread.set(Thread.currentThread().getName());
            stream.set(invocation.getArgument(0));
            started.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return List.of(new PomParserService.MavenDependency("g", "a", "1", "compile"));
        });
        when(client.downloadJar("g", "a", "1")).thenReturn(currentJar);
        when(client.downloadJar("g", "a", "2")).thenReturn(targetJar);
        when(engine.compareJars(currentJar.toFile(), targetJar.toFile())).thenReturn(List.of());
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(AsyncConfig.class, AuditJobRepository.class, AsyncAuditService.class);
            context.registerBean(PomParserService.class, () -> parser);
            context.registerBean(MavenCentralClient.class, () -> client);
            context.registerBean(AstDiffEngine.class, () -> engine);
            context.refresh();
            var jobs = context.getBean(AuditJobRepository.class);
            var controller = new AuditController(jobs, context.getBean(AsyncAuditService.class));
            var response = controller.analyzePomFile(file("<project/>"), "2").block(Duration.ofSeconds(5));
            assertNotNull(response);
            assertEquals(202, response.getStatusCode().value());
            assertEquals(AuditJob.Status.QUEUED, response.getBody().status());
            UUID id = response.getBody().jobId();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(thread.get().startsWith("ast-worker-"));
            assertEquals(AuditJob.Status.IN_PROGRESS, jobs.findById(id).orElseThrow().status());
            // Upload buffers were released, but the worker still has readable upload bytes.
            assertEquals("<project/>", new String(stream.get().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (jobs.findById(id).orElseThrow().status() == AuditJob.Status.IN_PROGRESS
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            var job = jobs.findById(id).orElseThrow();
            assertEquals(AuditJob.Status.COMPLETED, job.status());
            assertNotNull(job.completedAt());
            assertEquals(1, job.result().totalDependenciesParsed());
            verify(engine).compareJars(currentJar.toFile(), targetJar.toFile());
            assertFalse(Files.exists(currentJar));
            assertFalse(Files.exists(targetJar));
            assertEquals(200, controller.getStatus(id).getStatusCode().value());
            assertEquals(404, controller.getStatus(UUID.randomUUID()).getStatusCode().value());
        } finally {
            release.countDown();
            Files.deleteIfExists(currentJar);
            Files.deleteIfExists(targetJar);
        }
    }

    @Test
    void processingFailureMarksJobFailedAndClosesStream() throws Exception {
        var jobs = new AuditJobRepository();
        var parser = mock(PomParserService.class);
        when(parser.parseDependencies(any())).thenThrow(new IllegalArgumentException("Invalid POM"));
        var service = new AsyncAuditService(jobs, parser, mock(MavenCentralClient.class), mock(AstDiffEngine.class));
        var job = jobs.create();
        var stream = spy(new ByteArrayInputStream(new byte[0]));
        service.processAuditAsync(job.jobId(), stream);
        var failed = jobs.findById(job.jobId()).orElseThrow();
        assertEquals(AuditJob.Status.FAILED, failed.status());
        assertNotNull(failed.completedAt());
        assertEquals("ERROR: Invalid POM", failed.result().status());
        verify(stream).close();
        jobs.markInProgress(job.jobId());
        assertEquals(failed, jobs.findById(job.jobId()).orElseThrow());
    }

    @Test
    void rejectedSubmissionReturns503AndDoesNotLeaveQueuedJob() {
        var jobs = new AuditJobRepository();
        var service = mock(AsyncAuditService.class);
        doThrow(new TaskRejectedException("full")).when(service).processAuditAsync(any(), any(), anyString());
        var controller = new AuditController(jobs, service);
        var response = controller.analyzePomFile(file("<project/>"), "LATEST").block();
        assertNotNull(response);
        assertEquals(503, response.getStatusCode().value());
        assertEquals(AuditJob.Status.FAILED, jobs.findById(response.getBody().jobId()).orElseThrow().status());
        assertEquals("5", response.getHeaders().getFirst("Retry-After"));
    }

    private FilePart file(String content) {
        var file = mock(FilePart.class);
        when(file.content()).thenReturn(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        return file;
    }
}
