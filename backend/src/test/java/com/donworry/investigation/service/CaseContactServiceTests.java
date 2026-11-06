package com.donworry.investigation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.dto.RawViewRequest;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.RawViewReview;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.RawViewReviewRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.CustomerContactReader;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

// 사건 결제 1건의 주문자 정보 — 담당 · 열린 사건 · 사건 소속 결제만 (#619, ADR-0082 1절)
class CaseContactServiceTests {
    private static final Long CASE_ID = 24L;
    private static final Long PAYMENT_ID = 31L;
    private static final Long ASSIGNEE = 3L;
    private static final Long OTHER_STAFF = 4L;

    private final SecurityStaffGuard staffGuard = mock(SecurityStaffGuard.class);
    private final SecurityCaseRepository cases = mock(SecurityCaseRepository.class);
    private final CasePaymentRepository casePayments = mock(CasePaymentRepository.class);
    private final RawViewReviewRepository reviews = mock(RawViewReviewRepository.class);
    private final CustomerContactReader contactReader = mock(CustomerContactReader.class);
    private final StaffAccessRecorder accessRecorder = mock(StaffAccessRecorder.class);
    private final CaseContactService service =
            new CaseContactService(staffGuard, cases, casePayments, reviews, contactReader, accessRecorder);
    private final RawViewRequest body = new RawViewRequest("CB-2026-1107", "가맹점 대표번호 2회 통화 실패", "카드사 이의제기 고객 확인");

    // 담당이 아닌 직원이 고객 전화 원문을 보고, 시도도 기록에 남지 않는다
    @Test
    void rawView_byNonAssignee_isDeniedAndRecorded() {
        stubCase(openCase(), PAYMENT_ID);
        when(staffGuard.requireSecurityStaffId()).thenReturn(OTHER_STAFF);

        assertThatThrownBy(() -> service.rawView(CASE_ID, PAYMENT_ID, body))
                .isInstanceOfSatisfying(
                        InvestigationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvestigationErrorCode.CASE_ASSIGNEE_ONLY));
        verifyDenied(StaffAccessType.CONTACT_RAW_VIEW, StaffAccessDenyReason.NOT_ASSIGNEE);
        verify(contactReader, never()).ordererPhone(any());
        verify(reviews, never()).save(any());
    }

    // 종료된 사건을 핑계로 담당자였던 직원이 연락처를 계속 열어 본다 (가린 값 · 원문 모두)
    @Test
    void contact_closedCase_isDenied() {
        SecurityCase closed = openCase();
        closed.close(Instant.parse("2026-11-07T03:00:00Z"));
        stubCase(closed, PAYMENT_ID);
        when(staffGuard.requireSecurityStaffId()).thenReturn(ASSIGNEE);

        assertThatThrownBy(() -> service.masked(CASE_ID, PAYMENT_ID))
                .isInstanceOfSatisfying(
                        InvestigationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvestigationErrorCode.CONTACT_CASE_CLOSED));
        assertThatThrownBy(() -> service.rawView(CASE_ID, PAYMENT_ID, body))
                .isInstanceOfSatisfying(
                        InvestigationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvestigationErrorCode.CONTACT_CASE_CLOSED));
        ArgumentCaptor<StaffAccess> denied = ArgumentCaptor.forClass(StaffAccess.class);
        verify(accessRecorder, times(2)).recordDenied(denied.capture(), eq(StaffAccessDenyReason.CASE_CLOSED));
        assertThat(denied.getAllValues())
                .extracting(StaffAccess::accessType)
                .containsExactly(StaffAccessType.CONTACT_MASKED_VIEW, StaffAccessType.CONTACT_RAW_VIEW);
        verify(contactReader, never()).masked(any());
        verify(contactReader, never()).ordererPhone(any());
    }

    // 내 사건 번호에 사건 밖 결제 번호(99)를 넣어 다른 고객 연락처를 본다
    @Test
    void contact_paymentNotInCase_isDenied() {
        stubCase(openCase(), PAYMENT_ID);
        when(staffGuard.requireSecurityStaffId()).thenReturn(ASSIGNEE);

        assertThatThrownBy(() -> service.rawView(CASE_ID, 99L, body))
                .isInstanceOfSatisfying(
                        InvestigationException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(InvestigationErrorCode.PAYMENT_NOT_IN_CASE));
        verifyDenied(StaffAccessType.CONTACT_RAW_VIEW, StaffAccessDenyReason.PAYMENT_NOT_IN_CASE);
        verify(contactReader, never()).ordererPhone(any());
    }

    // 사후 검토에 안 쌓이면 ADMIN이 열람을 모르고, 기록에 원문이 복사되면 기록이 유출 경로가 된다
    @Test
    void rawView_createsReviewAndAccessLogWithoutRawValue() {
        stubCase(openCase(), PAYMENT_ID);
        when(staffGuard.requireSecurityStaffId()).thenReturn(ASSIGNEE);
        when(contactReader.ordererPhone(PAYMENT_ID)).thenReturn(Optional.of("010-1234-5678"));

        var response = service.rawView(CASE_ID, PAYMENT_ID, body);

        assertThat(response.ordererPhone()).isEqualTo("010-1234-5678");
        ArgumentCaptor<RawViewReview> review = ArgumentCaptor.forClass(RawViewReview.class);
        verify(reviews).save(review.capture());
        assertThat(review.getValue().getStaffId()).isEqualTo(ASSIGNEE);
        assertThat(review.getValue().getCaseId()).isEqualTo(CASE_ID);
        assertThat(review.getValue().getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(review.getValue().getDisputeRef()).isEqualTo("CB-2026-1107");
        ArgumentCaptor<StaffAccess> access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(accessRecorder).recordAllowed(access.capture());
        assertThat(access.getValue().accessType()).isEqualTo(StaffAccessType.CONTACT_RAW_VIEW);
        assertThat(access.getValue().paymentId()).isEqualTo(PAYMENT_ID);
        assertThat(access.getValue().toString()).doesNotContain("1234", "5678");
    }

    private void verifyDenied(StaffAccessType type, StaffAccessDenyReason reason) {
        ArgumentCaptor<StaffAccess> access = ArgumentCaptor.forClass(StaffAccess.class);
        verify(accessRecorder).recordDenied(access.capture(), eq(reason));
        assertThat(access.getValue().accessType()).isEqualTo(type);
        assertThat(access.getValue().caseId()).isEqualTo(CASE_ID);
    }

    private SecurityCase openCase() {
        SecurityCase securityCase =
                SecurityCase.open(7L, "7:42", CaseType.PAYMENT_FRAUD, Instant.parse("2026-11-07T01:00:00Z"));
        ReflectionTestUtils.setField(securityCase, "id", CASE_ID);
        securityCase.assignTo(ASSIGNEE, Instant.parse("2026-11-07T01:00:01Z"));
        return securityCase;
    }

    private void stubCase(SecurityCase securityCase, Long paymentId) {
        when(cases.findById(CASE_ID)).thenReturn(Optional.of(securityCase));
        when(casePayments.findByPaymentId(paymentId))
                .thenReturn(
                        Optional.of(CasePayment.link(securityCase, paymentId, Instant.parse("2026-11-07T01:00:00Z"))));
    }
}
