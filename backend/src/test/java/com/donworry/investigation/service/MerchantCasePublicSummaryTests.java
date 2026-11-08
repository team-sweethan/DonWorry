package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.donworry.investigation.entity.CaseStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class MerchantCasePublicSummaryTests {
    @ParameterizedTest
    @EnumSource(
            value = CaseStatus.class,
            names = {"OPEN", "INVESTIGATING", "DECISION_PENDING"})
    void undecided_doesNotPromiseCompletion(CaseStatus status) {
        assertThat(MerchantCasePublicSummary.forStatus(status).getFirst())
                .contains("확정되지 않았습니다")
                .doesNotContain("매입 완료", "취소 완료", "정산 예정일");
    }

    @ParameterizedTest
    @EnumSource(
            value = CaseStatus.class,
            names = {"ACTION_PENDING", "ACTION_FAILED"})
    void pendingOrFailed_isNotReportedAsCompleted(CaseStatus status) {
        assertThat(MerchantCasePublicSummary.forStatus(status).getFirst())
                .contains("아직 완료되지 않았습니다")
                .doesNotContain("매입 완료", "취소 완료", "지급 완료");
    }

    @Test
    void closed_doesNotAssumeAllPaymentsWereCaptured() {
        assertThat(MerchantCasePublicSummary.forStatus(CaseStatus.CLOSED).getFirst())
                .contains("각 결제의 실제 처리 결과")
                .doesNotContain("매입 완료", "취소 완료", "지급 완료");
    }

    @Test
    void unknown_doesNotInferAnOutcome() {
        assertThat(MerchantCasePublicSummary.forStatus(null).getFirst()).contains("확인할 수 없습니다");
    }
}
