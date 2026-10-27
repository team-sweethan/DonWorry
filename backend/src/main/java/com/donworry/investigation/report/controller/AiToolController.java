package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.CasePaymentsToolResponse;
import com.donworry.investigation.report.dto.FdsEvidenceToolResponse;
import com.donworry.investigation.report.service.AiToolService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// AI 서버 전용 읽기 Tool — 결제 · 판정 · Case를 바꾸는 Tool은 만들지 않는다 (ADR-0009 결정 1)
// 로그인 없이 결제 · FDS 근거를 내주므로 서비스 키 인증 전까지 기본으로 꺼 둔다 — #253에서 인증을 붙인 뒤 켠다
// P5 PaymentHttpController(payment.http.enabled)와 같은 방식 (ADR-0037 "인증 연결 전 기본 비활성")
@ConditionalOnProperty(name = "ai.tools.enabled", havingValue = "true")
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
}
