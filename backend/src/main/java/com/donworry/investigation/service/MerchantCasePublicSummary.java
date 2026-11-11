package com.donworry.investigation.service;

import com.donworry.investigation.entity.CaseStatus;
import java.util.List;

// 사장님 공개 안내는 사건 상태만 입력으로 받는다. 내부 보고서의 자유문장을 재사용하지 않는다.
final class MerchantCasePublicSummary {
    private MerchantCasePublicSummary() {}

    static List<String> forStatus(CaseStatus status) {
        if (status == null) {
            return List.of("현재 처리 상태를 확인할 수 없습니다. 잠시 후 다시 조회해 주세요.");
        }
        return switch (status) {
            case OPEN, INVESTIGATING, DECISION_PENDING -> List.of("결제를 확인하고 있습니다. 아직 처리 결과가 확정되지 않았습니다.");
            case ACTION_PENDING, ACTION_FAILED -> List.of("보안 검토 후 결제 처리가 아직 완료되지 않았습니다. 각 결제의 실제 처리 상태를 확인해 주세요.");
            case CLOSED -> List.of("사건 처리가 종료됐습니다. 각 결제의 실제 처리 결과를 확인해 주세요.");
        };
    }
}
