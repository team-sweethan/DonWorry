package com.donworry.identity.api;

// 보안 직원 · ADMIN의 고객정보 접근 기록 창구 (ADR-0082 4절)
public interface StaffAccessRecorder {
    // 허용 — 호출 쪽 트랜잭션 안에서만 부른다(밖이면 예외). 기록 · 업무가 함께 커밋되거나 함께 롤백된다.
    // 저장에 실패하거나 직원 세션이 아니면 예외를 던진다. 호출 쪽은 데이터를 내보내지 않는다
    void recordAllowed(StaffAccess access);

    // 거부 — 호출 쪽과 따로 커밋해 거부 응답 롤백 뒤에도 남는다. 실패해도 예외를 던지지 않는다(원래 거부 응답 유지)
    void recordDenied(StaffAccess access, StaffAccessDenyReason reason);
}
