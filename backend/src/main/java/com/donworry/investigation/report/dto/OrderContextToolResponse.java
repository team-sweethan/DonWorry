package com.donworry.investigation.report.dto;

import java.time.Instant;
import java.util.List;
import lombok.Builder;

// AI Tool get_order_context 응답 — 사건 결제마다 가맹점이 결제 준비 때 보낸 주문 정보 (#634, ADR-0009)
// 주문자 이름 · 전화는 원문 · 가린 값 · 지문 어느 것도 싣지 않는다. 같은 주문 · 같은 전화인지는 묶음 번호로만 (ADR-0082)
// orderRef · orderName은 가맹점이 쓴 글자라 ai-server가 AI 입력에서 빼고 보고서 표에만 코드로 싣는다 (프롬프트 주입 차단)
@Builder
public record OrderContextToolResponse(
        String caseId, int evidenceVersion, String source, Instant retrievedAt, List<Payment> payments) {

    // provided=false면 결제 준비 세션이 없어 나머지 칸이 비어 있다 ("주문 정보 없음")
    // orderGroup: 이 사건 안에서 주문번호가 같은 결제끼리 같은 번호 (1부터, 사건에 묶인 순서)
    // orderToPaymentSeconds: 주문 생성 → 결제 요청까지 걸린 초. 가맹점 시계 기준 주문 시각이라 음수일 수 있다
    // ordererPhoneGroup: 이 사건 안에서 주문자 전화가 같은 결제끼리 같은 번호. 전화를 받지 않았으면 null
    // pickupDiffers: 픽업자가 주문자와 다른지. 픽업자를 받지 않았으면 null
    @Builder
    public record Payment(
            String paymentId,
            boolean provided,
            String orderRef,
            String orderName,
            Integer orderGroup,
            Long orderToPaymentSeconds,
            boolean ordererProvided,
            Integer ordererPhoneGroup,
            Boolean pickupDiffers) {}
}
