package com.changelogengine.dto;

public record AuditRequest(
        String groupId,
        String artifactId,
        String currentVersion,
        String targetVersion
) {}