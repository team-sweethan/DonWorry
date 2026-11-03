package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;

// AI Tool find_similar_cases 응답 — 이 Case와 비슷한 종료 사건과 결제별 판정 결과 (#296, ADR-0009 Tool 7번째)
// similarity: SAME_CUSTOMER(같은 가맹점 · 고객) / SHARED_RULES(적중 규칙 2개 이상 겹침). 최대 5건, totalMatched는 전체 건수
// 결과는 직원 판정 건수만 둔다 — "사기 확정" 같은 해석은 하지 않는다
public record SimilarCasesToolResponse(
        String caseId,
        int evidenceVersion,
        String source,
        Instant retrievedAt,
        List<SimilarCase> cases,
        int totalMatched) {

    public record SimilarCase(
            String caseId,
            String similarity,
            List<SharedRule> sharedRules,
            Instant closedAt,
            int acceptCount,
            int rejectCount) {}

    public record SharedRule(String code, String name) {}
}
