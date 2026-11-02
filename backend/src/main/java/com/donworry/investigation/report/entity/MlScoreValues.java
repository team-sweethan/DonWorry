package com.donworry.investigation.report.entity;

import java.util.Map;
import lombok.Builder;

// ai-server 채점 성공 응답 중 ml_shadow_scores에 남길 값 (#412)
@Builder
public record MlScoreValues(
        double fraudProbability,
        double threshold,
        MlRiskLevel mlRiskLevel,
        Map<String, Object> features,
        int latencyMs) {}
