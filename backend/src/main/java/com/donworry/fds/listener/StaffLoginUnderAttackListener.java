package com.donworry.fds.listener;

import com.donworry.fds.service.InsiderDetectionService;
import com.donworry.identity.api.StaffLoginUnderAttack;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P1 로그인 공격 알림이 커밋된 뒤 받아 SEC-INS-10을 본다 (#770, ADR-0082)
// 실패하면 event_publication에 미완료로 남고, 재전달돼도 같은 로그인 시각의 INS-10 경보가 있으면 다시 열지 않는다
@Component
@RequiredArgsConstructor
public class StaffLoginUnderAttackListener {
    private final InsiderDetectionService detectionService;

    @ApplicationModuleListener
    public void on(StaffLoginUnderAttack event) {
        detectionService.detectLoginUnderAttack(event);
    }
}
