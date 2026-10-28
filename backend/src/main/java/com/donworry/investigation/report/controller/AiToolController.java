package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.client.AiServerClient;
import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.service.AiToolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI 서버 전용 읽기 Tool — 결제 · 판정 · Case를 바꾸는 Tool은 만들지 않는다 (ADR-0009 결정 1)
// 결제 · FDS 근거를 내주므로 서비스 키가 맞아야 답한다 (#253). P1 보안 체인으로 옮길 때까지는 기본으로 꺼 둔다
// P5 PaymentHttpController(payment.http.enabled)와 같은 방식 (ADR-0037 "인증 연결 전 기본 비활성")
@ConditionalOnProperty(name = "ai.tools.enabled", havingValue = "true")
@RestController
@RequestMapping("/internal/ai/tools")
@RequiredArgsConstructor
public class AiToolController {

    private final AiToolService aiToolService;
    private final AiServerKeyGuard keyGuard;

    @GetMapping("/get_case_payments")
    public CasePaymentsToolResponse casePayments(
            @RequestHeader(name = AiServerClient.API_KEY_HEADER, required = false) String apiKey,
            @RequestParam Long caseId) {
        keyGuard.check(apiKey);
        return aiToolService.casePayments(caseId);
    }

    // paymentIds를 주면 그 결제만, 비우면 Case에 묶인 결제 전체를 본다
    @GetMapping("/get_fds_evidence")
    public FdsEvidenceToolResponse fdsEvidence(
            @RequestHeader(name = AiServerClient.API_KEY_HEADER, required = false) String apiKey,
            @RequestParam Long caseId,
            @RequestParam(required = false) List<Long> paymentIds) {
        keyGuard.check(apiKey);
        return aiToolService.fdsEvidence(caseId, paymentIds);
    }
}
