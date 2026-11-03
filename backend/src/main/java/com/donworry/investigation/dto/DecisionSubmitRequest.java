package com.donworry.investigation.dto;

import com.donworry.investigation.entity.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// 결제별 판정 요청 — 화면 SubmitDecisionsRequest와 같은 모양
// 판정 직원은 받지 않는다. 서버가 로그인 세션(identity CurrentStaff)으로 정한다 (#160)
public record DecisionSubmitRequest(
        // 직원이 화면에서 확인한 Case 증거 버전. 현재 버전과 다르면 거부한다 (fds 정본 03 §3.5)
        @NotNull Integer evidenceVersion,
        // AI 보고서 최종 실패 후 수동 판정이면 null
        Long reportId,
        // [null] 같은 빈 항목도 400으로 막는다 — @Valid는 null 요소를 건너뛴다
        @NotEmpty List<@NotNull @Valid Item> decisions) {

    public record Item(
            @NotNull Long casePaymentId,
            @NotNull Decision decision,
            @NotBlank String reason) {}
}
