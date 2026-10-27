package com.donworry.fds.api;

import java.time.Instant;
import java.util.UUID;

public record FdsEvaluationFailureAnalyzed(
        Long analysisId,
        UUID failureReportId,
        UUID originalEvaluationRequestId,
        UUID evaluationRequestId,
        Long paymentId,
        FdsFailureDisposition disposition,
        String reasonCode,
        Instant analyzedAt) {}
