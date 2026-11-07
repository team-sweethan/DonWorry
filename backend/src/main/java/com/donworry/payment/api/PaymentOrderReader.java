package com.donworry.payment.api;

import java.util.Collection;
import java.util.List;

// AI 조사 기본 조회용 주문 요약 (#634, ADR-0009) — 내부 조회 전용
// 호출자가 사건에 속한 결제 ID만 넘긴다. 주문자 이름 · 전화는 원문 · 가린 값 · 지문 어느 것도 내보내지 않는다 (ADR-0082)
// 같은 전화인지는 이 창구 안에서 지문으로 비교해 묶음 번호로만 알린다 — 번호는 한 번 부른 결과 안에서만 뜻이 있다
// 직원이 연락처를 여는 CustomerContactReader(결제 1건 · 접근 기록)와 다르다: 값이 나가지 않으므로 기록을 남기지 않는다
public interface PaymentOrderReader {
    // 넘긴 순서대로 결제마다 하나. 결제 준비 세션이 없는 결제는 provided=false
    // null · 0 이하 ID가 있으면 IllegalArgumentException
    List<PaymentOrderSummary> findOrders(Collection<Long> paymentIds);
}
