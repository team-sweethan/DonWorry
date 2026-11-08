package com.donworry.fds.service;

import com.donworry.fds.api.InsiderExportGuard;
import com.donworry.identity.api.StaffAccountLocker;
import com.donworry.identity.api.StaffLockReason;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

// 내보내기 문지기 구현 (#651) — 높음 이상 경보가 없으면 통과, 있으면 INS-05 경보 → 계정 정지 → 차단
// 주의 경보만 열려 있으면 통과한다: 정상 업무(예: 주간 보고용 전체 내보내기)를 주의 하나로 정지시키지 않는다 (ADR-0082 3.1)
// 반출을 막는 게 먼저다: 경보 저장이 실패해도 정지하고, 정지가 실패해도 차단한다
@Slf4j
@Service
@RequiredArgsConstructor
class InsiderExportGuardService implements InsiderExportGuard {
    private final InsiderDetectionService detection;
    private final StaffAccountLocker locker;

    @Override
    public boolean allowExport(Long staffId) {
        // 사전 확인은 새 트랜잭션에서 — 호출 쪽의 오래된 스냅샷으로 "경보 없음"을 보고 통과시키지 않는다 (Codex 리뷰)
        if (!detection.hasExportBlockingAlert(staffId)) return true;
        Long alertId = null;
        try {
            alertId = detection.raiseExportBlock(staffId, Instant.now());
            // 확인과 저장 사이에 관리자가 높음 이상 경보를 모두 닫았으면 막지 않는다
            if (alertId == null) return true;
        } catch (RuntimeException e) {
            log.error("INS-05 경보 저장 실패 — 계정 정지는 계속 staffId={}", staffId, e);
        }
        try {
            locker.lockBySystem(staffId, StaffLockReason.INSIDER_EXPORT_BLOCKED, alertId);
        } catch (RuntimeException e) {
            log.error("INS-05 계정 정지 실패 — 내보내기는 차단 staffId={} alertId={}", staffId, alertId, e);
        }
        return false;
    }
}
