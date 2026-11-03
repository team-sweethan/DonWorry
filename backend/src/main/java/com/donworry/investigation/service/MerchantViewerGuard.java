package com.donworry.investigation.service;

import com.donworry.identity.api.CurrentStaff;
import com.donworry.identity.api.StaffSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

// 고객사 "보안 조치 결과" 조회의 로그인 사용자 확인 (fds 정본 02 §2.12 ②, #205)
// 조회 가맹점은 요청 값이 아니라 세션의 merchantId로만 정한다 — 다른 가맹점 ID를 넣어 볼 수 없게 한다
@Component
@RequiredArgsConstructor
public class MerchantViewerGuard {

    // identity.api가 내보내는 역할 · 권한 이름 (ADR-0020)
    private static final String OPERATOR = "OPERATOR";
    private static final String FDS_VIEW = "FDS_VIEW";

    private final CurrentStaff currentStaff;

    // 사장님(OPERATOR) 중 FDS 조회 권한이 있는 계정만 통과한다. 보안 직원 · 관리자는 각자 화면을 쓴다
    // merchantId 없는 사장님은 비정상 데이터라 전체 조회로 넓히지 않고 거부한다 (ADR-0020)
    public Long requireMerchantId() {
        StaffSummary viewer = currentStaff
                .findCurrent()
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.LOGIN_REQUIRED));
        if (!OPERATOR.equals(viewer.role())
                || viewer.merchantId() == null
                || !viewer.permissions().contains(FDS_VIEW)) {
            throw new InvestigationException(InvestigationErrorCode.MERCHANT_VIEWER_ONLY);
        }
        return viewer.merchantId();
    }
}
