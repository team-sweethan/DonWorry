package com.donworry.payment.dto;

import java.time.Instant;
import lombok.Builder;

// 거래 내역 한 줄 (#363). 카드 토큰 · IP · 기기 ID · 이메일 원문과 승인 참조는 담지 않는다
// 카드 · 접속 · 기기 · 이메일은 가린 값이어도 목록에 싣지 않는다 (#732) — 1건 상세(PaymentTransactionDetailResponse)에서 열람 기록과 함께 본다
@Builder
public record PaymentTransactionResponse(
        Long paymentId,
        Instant createdAt,
        Long merchantId,
        String customerRef,
        String orderRef,
        Long attemptNo,
        Long amount,
        String paymentStatus,
        String fdsResult,
        Long evaluationId,
        // 이 직원이 거래 상세를 열 수 있는지 (#732) — 보안 직원은 담당 사건 고객의 결제만. 화면 편의이고 판정은 상세 조회가 한다
        boolean detailAllowed) {}
