package com.donworry.investigation.dto;

import com.donworry.investigation.entity.Decision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

// 결제별 판정 요청 — 화면 SubmitDecisionsRequest와 같은 모양
// staffId는 로그인 사용자 조회 공개 계약(identity api)이 생기기 전까지 요청으로 받는다
public record DecisionSubmitRequest(
        @NotNull Long staffId,
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
