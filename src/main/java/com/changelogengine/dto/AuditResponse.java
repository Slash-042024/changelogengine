package com.changelogengine.dto;

import java.io.Serializable;
import java.util.List;

public record AuditResponse(
        String status,
        int totalDependenciesParsed,
        int breakingChangesFound,
        List<BreakingChangeDto> details
) implements Serializable {
    private static final long serialVersionUID = 1L;
}
