package com.changelogengine.controller;

import com.changelogengine.dto.AuditJob;
import com.changelogengine.dto.AuditResponse;
import com.changelogengine.service.AsyncAuditService;
import com.changelogengine.service.AuditJobRepository;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private static final int MAX_POM_BYTES = 10 * 1024 * 1024;
    private final AuditJobRepository jobs;
    private final AsyncAuditService asyncAuditService;

    public AuditController(AuditJobRepository jobs, AsyncAuditService asyncAuditService) {
        this.jobs = jobs;
        this.asyncAuditService = asyncAuditService;
    }

    public record JobSubmission(UUID jobId, AuditJob.Status status) {}

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<JobSubmission>> analyzePomFile(
            @RequestPart("file") FilePart filePart,
            @RequestParam(value = "targetVersion", defaultValue = "LATEST") String targetVersion) {
        return DataBufferUtils.join(filePart.content(), MAX_POM_BYTES)
                .map(buffer -> {
                    byte[] bytes;
                    try {
                        bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                    } finally {
                        DataBufferUtils.release(buffer);
                    }
                    if (bytes.length == 0) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "POM file is empty");
                    }
                    AuditJob job = jobs.create();
                    // The worker owns this stream; no pooled request buffers escape.
                    ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
                    try {
                        asyncAuditService.processAuditAsync(job.jobId(), stream, targetVersion);
                    } catch (TaskRejectedException e) {
                        // This stream owns no external resources and is dropped on rejection.
                        jobs.fail(job.jobId(), new AuditResponse("ERROR: Audit queue is full", 0, 0, List.of()));
                        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .header("Retry-After", "5")
                                .body(new JobSubmission(job.jobId(), AuditJob.Status.FAILED));
                    }
                    return ResponseEntity.accepted()
                            .location(URI.create("/api/v1/audit/status/" + job.jobId()))
                            .body(new JobSubmission(job.jobId(), AuditJob.Status.QUEUED));
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "POM file is empty")))
                .onErrorMap(DataBufferLimitException.class,
                        e -> new ResponseStatusException(HttpStatus.valueOf(413), "POM file exceeds 10 MB", e));
    }

    @GetMapping("/status/{jobId}")
    public ResponseEntity<AuditJob> getStatus(@PathVariable("jobId") UUID jobId) {
        return jobs.findById(jobId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
