package com.donworry.fds.api;

// 내부자 사건 판정 때 그 직원의 열린 경보를 모두 닫는 창구 (#670, ADR-0082) — P3 내부자 사건이 쓴다
// 열린 채로 정지를 풀면 다음 내보내기 시도에서 바로 다시 정지되기 때문에, 판정 3개 모두 닫는다
// 호출 쪽 트랜잭션에 참여해 판정 저장과 함께 커밋 · 롤백된다. 탐지와 같은 직원 줄 세우기 행을 먼저 잡는다
public interface InsiderAlertCloser {

    // 닫은 경보 수를 돌려준다. 열린 경보가 없으면 0
    int closeOpenAlerts(Long staffId, Long adminId, String adminName, String memo);
}
