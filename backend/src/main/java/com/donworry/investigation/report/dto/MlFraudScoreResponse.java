package com.donworry.investigation.report.dto;

import com.donworry.investigation.report.entity.MlRiskLevel;
import java.util.Map;
import lombok.Builder;

// ai-server ML 채점 성공 응답 (app/schemas/ml.py FraudScoreResponse, #414)
// mlRiskLevel은 분석용 표시이고 결제 판정이 아니다. 모르는 값이 오면 읽기 실패로 본다
@Builder
public record MlFraudScoreResponse(
        long evaluationId,
        String modelVersion,
        double fraudProbability,
        double threshold,
        MlRiskLevel mlRiskLevel,
        // 모델에 들어간 값 그대로 (숫자 · 문자열) — 확률 근거 확인용
        Map<String, Object> features,
        int latencyMs) {}
