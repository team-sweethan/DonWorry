package com.donworry.fds.api;

import java.util.List;
import java.util.Map;

public record FdsEvaluationPage(
        List<FdsEvaluationSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<FdsResult, Long> resultCounts) {}
