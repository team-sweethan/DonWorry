package com.donworry.fds.api;

import java.util.List;
import java.util.Map;
import lombok.Builder;

@Builder
public record FdsEvaluationPage(
        List<FdsEvaluationSummary> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        Map<FdsResult, Long> resultCounts) {}
