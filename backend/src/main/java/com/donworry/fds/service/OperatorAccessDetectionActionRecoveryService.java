package com.donworry.fds.service;

import com.donworry.fds.entity.OperatorAccessDetectionActionStatus;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 커밋 직후 프로세스가 중단된 경우에도 REQUESTED 조치를 다시 실행한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class OperatorAccessDetectionActionRecoveryService {
    private final OperatorAccessDetectionRepository detections;
    private final OperatorAccessDetectionActionService actions;

    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    public void retryRequestedActions() {
        try {
            detections
                    .findTop100ByActionStatusOrderByDetectedAtAsc(OperatorAccessDetectionActionStatus.REQUESTED)
                    .forEach(detection -> actions.suspend(detection.getId(), detection.getUserId()));
        } catch (RuntimeException exception) {
            log.error(
                    "사장님 반복 권한 위반 정지 재시도 조회 실패 errorType={}",
                    exception.getClass().getSimpleName());
        }
    }
}
