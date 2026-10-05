package com.changelogengine.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
public class MavenCentralClient {

    private static final Logger log = LoggerFactory.getLogger(MavenCentralClient.class);
    private final WebClient webClient;

    public MavenCentralClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .baseUrl("https://repo1.maven.org/maven2")
                .build();
    }

    /**
     * Downloads a JAR binary from Maven Central and saves it to a local temporary file on Windows.
     *
     * @param groupId e.g. "org.springframework.boot"
     * @param artifactId e.g. "spring-boot-starter-web"
     * @param version e.g. "3.3.4"
     * @return Path pointing to the downloaded temporary JAR file
     */
    public Path downloadJar(String groupId, String artifactId, String version) {
        String groupPath = groupId.replace('.', '/');
        String jarFileName = String.format("%s-%s.jar", artifactId, version);
        String relativeUrl = String.format("/%s/%s/%s/%s", groupPath, artifactId, version, jarFileName);

        log.info("Fetching target artifact from Maven Central: {}", relativeUrl);

        try {
            byte[] jarBytes = this.webClient.get()
                    .uri(relativeUrl)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .onErrorResume(e -> {
                        log.error("Failed to download JAR from Maven Central: {}", relativeUrl, e);
                        return Mono.empty();
                    })
                    .block();

            if (jarBytes == null || jarBytes.length == 0) {
                throw new RuntimeException("Artifact not found on Maven Central or payload empty: " + relativeUrl);
            }

            // Create temporary file on Windows disk
            Path tempJarPath = Files.createTempFile("changelog_artifact_", ".jar");
            Files.write(tempJarPath, jarBytes);

            log.info("Saved downloaded JAR to temp file: {}", tempJarPath.toAbsolutePath());
            return tempJarPath;

        } catch (Exception e) {
            log.error("Error encountered while handling Maven Central download", e);
            throw new RuntimeException("Maven artifact resolution failed", e);
        }
    }
}