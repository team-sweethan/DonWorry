package com.donworry.fds.listener;

import com.donworry.fds.service.InsiderDetectionService;
import com.donworry.identity.api.StaffAccessRecorded;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P1 접근 기록이 커밋된 뒤 받아 내부자 탐지를 돌린다 (#516, ADR-0082)
// 실패하면 event_publication에 미완료로 남고, 재전달돼도 근거는 기록 번호로 한 번만 붙는다
// 기본 REQUIRES_NEW 트랜잭션 안에서 직원별 행 잠금을 잡고 커밋 때 푼다 (OperatorAccessDeniedListener의 메모리 잠금과 다른 이유: 서버 여러 대)
@Component
@RequiredArgsConstructor
public class StaffAccessRecordedListener {
    private final InsiderDetectionService detectionService;

    @ApplicationModuleListener
    public void on(StaffAccessRecorded event) {
        detectionService.detect(event);
    }
}
