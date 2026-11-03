package com.donworry.fds.api;

import java.time.Instant;
import java.util.List;

public record FdsRuleCatalogEntry(
        String ruleCode,
        String category,
        String ruleName,
        String condition,
        String resultDescription,
        String example,
        String standaloneDescription,
        boolean immediateDeny,
        long recentMatchCount,
        Instant lastMatchedAt,
        List<Long> dailyMatchCounts) {}
