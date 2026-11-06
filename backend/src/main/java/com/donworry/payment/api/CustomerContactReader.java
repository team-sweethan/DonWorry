package com.donworry.payment.api;

import java.util.Optional;

// 사건 담당 보안 직원의 주문자 정보 열람 (ADR-0082 1 · 2절, #619) — 내부 조회 전용
// 호출자가 "담당 · 열린 사건 · 사건에 속한 결제"를 확인한 결제 ID 하나로만 부른다. 여러 결제를 한 번에 읽는 창구는 두지 않는다
// 연락처가 없거나 키가 없으면 empty · NO_CONTACT. 원문은 ordererPhone 하나만 이 경계 밖으로 나간다
public interface CustomerContactReader {

    // 가린 값 — 이름 홍*동 · 전화 010-****-5678 · 픽업자는 "주문자와 다름" 여부만
    Optional<MaskedContact> masked(Long paymentId);

    // 대조 — 입력 번호(하이픈 · 공백 허용)가 이 결제 주문자 전화와 같은지만. 형식이 틀리면 IllegalArgumentException
    ContactMatchResult matchOrdererPhone(Long paymentId, String phone);

    // 전화 원문(010-1234-5678 모양). 호출 쪽은 저장 · 로그 · 기록에 쓰지 않는다
    Optional<String> ordererPhone(Long paymentId);
}
