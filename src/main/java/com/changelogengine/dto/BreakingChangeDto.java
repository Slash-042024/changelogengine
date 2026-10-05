package com.changelogengine.dto;

public record BreakingChangeDto(
        String className,
        String methodName,
        String description,
        String severity
) {}