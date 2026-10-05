package com.changelogengine.service;

import com.changelogengine.dto.AuditResponse;
import com.changelogengine.dto.BreakingChangeDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class AsyncAuditService {
    private static final Logger log = LoggerFactory.getLogger(AsyncAuditService.class);
    private final AuditJobRepository jobs;
    private final PomParserService pomParserService;
    private final MavenCentralClient mavenCentralClient;
    private final AstDiffEngine astDiffEngine;

    public AsyncAuditService(AuditJobRepository jobs, PomParserService pomParserService,
                             MavenCentralClient mavenCentralClient, AstDiffEngine astDiffEngine) {
        this.jobs = jobs;
        this.pomParserService = pomParserService;
        this.mavenCentralClient = mavenCentralClient;
        this.astDiffEngine = astDiffEngine;
    }

    @Async("astTaskExecutor")
    public void processAuditAsync(UUID jobId, InputStream inputStream) {
        processAuditAsync(jobId, inputStream, "LATEST");
    }

    @Async("astTaskExecutor")
    public void processAuditAsync(UUID jobId, InputStream inputStream, String targetVersion) {
        int dependencyCount = 0;
        List<BreakingChangeDto> changes = new ArrayList<>();
        try (InputStream stream = inputStream) {
            jobs.markInProgress(jobId);
            log.info("Processing audit job {}", jobId);
            List<PomParserService.MavenDependency> dependencies = pomParserService.parseDependencies(stream);
            dependencyCount = dependencies.size();
            for (PomParserService.MavenDependency dep : dependencies) {
                if (dep.version() == null || "MANAGED_BY_PARENT".equals(dep.version())) {
                    continue;
                }
                Path currentJar = null;
                Path targetJar = null;
                try {
                    currentJar = mavenCentralClient.downloadJar(dep.groupId(), dep.artifactId(), dep.version());
                    String resolvedVersion = "LATEST".equalsIgnoreCase(targetVersion) ? dep.version() : targetVersion;
                    targetJar = resolvedVersion.equals(dep.version()) ? currentJar
                            : mavenCentralClient.downloadJar(dep.groupId(), dep.artifactId(), resolvedVersion);
                    changes.addAll(astDiffEngine.compareJars(currentJar.toFile(), targetJar.toFile()));
                } finally {
                    deleteJar(targetJar);
                    if (currentJar != null && !currentJar.equals(targetJar)) {
                        deleteJar(currentJar);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Audit job {} failed", jobId, e);
            jobs.fail(jobId, new AuditResponse("ERROR: " + e.getMessage(), dependencyCount,
                    changes.size(), List.copyOf(changes)));
            return;
        }
        jobs.complete(jobId, new AuditResponse("SUCCESS", dependencyCount, changes.size(), List.copyOf(changes)));
        log.info("Completed audit job {}", jobId);
    }

    private void deleteJar(Path jar) {
        if (jar != null) {
            try {
                Files.deleteIfExists(jar);
            } catch (Exception e) {
                log.warn("Could not delete temporary JAR {}", jar, e);
            }
        }
    }
}
