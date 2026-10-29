package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.dto.SimilarCasesToolResponse;
import com.donworry.investigation.report.service.AiToolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI 서버 전용 읽기 Tool — 결제 · 판정 · Case를 바꾸는 Tool은 만들지 않는다 (ADR-0009 결정 1)
// ai-server를 켰을 때만 경로가 생기고, 서비스 키 확인은 identity의 /internal/ai/** 보안 체인이 먼저 한다 (ADR-0041)
@ConditionalOnProperty(name = "ai.server.enabled", havingValue = "true")
@RestController
@RequestMapping("/internal/ai/tools")
@RequiredArgsConstructor
public class AiToolController {

    private final AiToolService aiToolService;

    @GetMapping("/get_case_payments")
    public CasePaymentsToolResponse casePayments(@RequestParam Long caseId) {
        return aiToolService.casePayments(caseId);
    }

    // paymentIds를 주면 그 결제만, 비우면 Case에 묶인 결제 전체를 본다
    @GetMapping("/get_fds_evidence")
    public FdsEvidenceToolResponse fdsEvidence(
            @RequestParam Long caseId, @RequestParam(required = false) List<Long> paymentIds) {
        return aiToolService.fdsEvidence(caseId, paymentIds);
    }

    // 이 Case와 비슷한 종료 사건 (#296) — 같은 고객 · 같은 규칙 조합, 최대 5건
    @GetMapping("/find_similar_cases")
    public SimilarCasesToolResponse similarCases(@RequestParam Long caseId) {
        return aiToolService.similarCases(caseId);
    }
}
