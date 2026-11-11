package com.donworry.investigation.listener;

import com.donworry.fds.api.InsiderAlertRaised;
import com.donworry.investigation.service.InsiderCaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

// P2 높음 이상 내부자 경보를 받아 내부자 사건을 열거나 열린 사건에 묶는다 (#670, ADR-0082)
// P2가 롤백한 경보로 사건을 만들지 않도록 발행 트랜잭션의 커밋 후에 받는다
// 처리에 실패하면 예외가 그대로 나가 EVENT_PUBLICATION에 미완료로 남고, 재전달 때 같은 경보는 한 줄로만 묶인다
@Component
@RequiredArgsConstructor
public class InsiderAlertRaisedListener {

    private final InsiderCaseService insiderCaseService;

    @ApplicationModuleListener
    public void on(InsiderAlertRaised event) {
        insiderCaseService.onAlertRaised(event);
    }
}
