package com.donworry.fds.api;

import java.time.Instant;

// 직원 담당 사건 범위 질문지 (#643, ADR-0082 합의 3 (가)) — INS-08 · 09가 "이 조회가 맡은 업무와 이어지는가"를 볼 때 쓴다
// 사건은 investigation 소유라 investigation이 구현한다(investigation → fds.api 방향). 실행 중에는 fds가 동기로 답을 받는다
// 모듈을 서비스로 나누면 담당 변경 이벤트 → fds 사본 표 방식으로 바꾼다 (ADR-0082 고려한 대안)
public interface StaffCaseScopeReader {
    // 지금 담당인 열린 사건 + closedAfter 이후에 닫힌 담당 사건의 가맹점 · 고객
    StaffCaseScope findScope(Long staffId, Instant closedAfter);
}
