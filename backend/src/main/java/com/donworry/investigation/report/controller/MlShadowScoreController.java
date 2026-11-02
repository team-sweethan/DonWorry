package com.donworry.investigation.report.controller;

import com.donworry.investigation.report.dto.MlShadowScoreResponse;
import com.donworry.investigation.report.service.MlShadowQueryService;
import com.donworry.investigation.service.SecurityStaffGuard;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 거래 내역 "ML 참고" 칸 (#441) — 결제 번호(여러 개) → 지금 모델의 ML Shadow 기록. 사건 칸과 같이 보안 직원만
// 결제 판정에 쓰지 않는 참고 점수다 (ADR-0061 결정 1)
@RestController
@RequestMapping("/api/ml-shadow-scores")
@RequiredArgsConstructor
public class MlShadowScoreController {

    private final MlShadowQueryService mlShadowQueryService;
    private final SecurityStaffGuard securityStaffGuard;

    @GetMapping
    public List<MlShadowScoreResponse> scores(@RequestParam(required = false) List<Long> paymentIds) {
        securityStaffGuard.requireSecurityStaffId();
        return mlShadowQueryService.findByPayments(paymentIds);
    }
}
