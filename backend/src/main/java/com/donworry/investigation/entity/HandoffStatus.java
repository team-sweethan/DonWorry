package com.donworry.investigation.entity;

// 금융 조치 인계 상태 (fds 정본 03 §3.3 ⑦)
// ACCEPTED는 실행 성공이 아니라 담당 모듈이 요청을 영속 접수했다는 뜻이다
public enum HandoffStatus {
    REQUESTED,
    ACCEPTED,
    FAILED
}
