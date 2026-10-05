package com.changelogengine.dto;

import java.util.List;

public record AuditResponse(
        String status,
        int totalDependenciesParsed,
        int breakingChangesFound,
        List<BreakingChangeDto> details
) {}