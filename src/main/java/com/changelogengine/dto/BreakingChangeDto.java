package com.changelogengine.dto;

import java.io.Serializable;

public record BreakingChangeDto(
        String className,
        String methodName,
        String description,
        String severity
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
