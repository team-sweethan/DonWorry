package com.donworry.fds.service;

import com.donworry.fds.entity.OperatorAccessDetectionActionStatus;
import com.donworry.fds.repository.OperatorAccessDetectionRepository;
import com.donworry.identity.api.OperatorAccountStatusReader;
import com.donworry.identity.api.OperatorAccountSuspender;
import com.donworry.identity.api.OperatorAccountSuspensionException;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 탐지·증적 커밋 뒤 독립 트랜잭션에서 P1 정지와 조치 결과 기록을 수행한다. */
@Service
@Slf4j
@RequiredArgsConstructor
class OperatorAccessDetectionActionService {
    private final OperatorAccessDetectionRepository detections;
    private final OperatorAccountSuspender suspender;
    private final OperatorAccountStatusReader statusReader;
    private final Clock clock;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void suspend(Long detectionId, Long userId) {
        var detection = detections.findById(detectionId).orElse(null);
        if (detection == null) return;
        var currentActivation = statusReader
                .findById(userId)
                .map(value -> value.lastActivatedAt())
                .orElse(null);
        if (currentActivation != null
                && (detection.getLastActivatedAt() == null
                        || currentActivation.isAfter(detection.getLastActivatedAt()))) {
            detection.complete(
                    OperatorAccessDetectionActionStatus.SKIPPED_REACTIVATED, "ACCOUNT_REACTIVATED", clock.instant());
            return;
        }
        try {
            suspender.suspend(userId);
            detection.complete(OperatorAccessDetectionActionStatus.SUCCEEDED, null, clock.instant());
        } catch (OperatorAccountSuspensionException exception) {
            var status = exception.getReason() == OperatorAccountSuspensionException.Reason.USER_NOT_FOUND
                    ? OperatorAccessDetectionActionStatus.FAILED_USER_NOT_FOUND
                    : OperatorAccessDetectionActionStatus.FAILED_NOT_OPERATOR;
            detection.complete(status, exception.getReason().name(), clock.instant());
        } catch (RuntimeException exception) {
            detection.complete(
                    OperatorAccessDetectionActionStatus.FAILED_UNEXPECTED, "SUSPEND_FAILED", clock.instant());
            log.error(
                    "사장님 반복 권한 위반 정지 실패 detectionId={} errorType={}",
                    detectionId,
                    exception.getClass().getSimpleName());
        }
    }
}
