package com.donworry.investigation.service;

import com.donworry.identity.api.StaffAccess;
import com.donworry.identity.api.StaffAccessDenyReason;
import com.donworry.identity.api.StaffAccessFieldType;
import com.donworry.identity.api.StaffAccessRecorder;
import com.donworry.identity.api.StaffAccessType;
import com.donworry.investigation.dto.CaseContactResponse;
import com.donworry.investigation.dto.ContactMatchResponse;
import com.donworry.investigation.dto.RawViewRequest;
import com.donworry.investigation.dto.RawViewResponse;
import com.donworry.investigation.entity.RawViewReview;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.RawViewReviewRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.payment.api.ContactMatchResult;
import com.donworry.payment.api.CustomerContactReader;
import com.donworry.payment.api.MaskedContact;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 사건 결제 1건의 주문자 정보 3단계 (#619, ADR-0082 1절) — 가린 값 → 번호 대조 → 전화 원문
// 세 동작 모두 "열린 사건 · 담당자 · 사건에 속한 결제"만. CaseAccessGuard와 달리 종료된 사건도 막는다
// (종료 사건 열람은 비슷한 과거 사건 비교용이고, 연락처는 그 업무에 필요 없다)
// 원문은 승인 없이 서버 조건으로 열고, 매번 ADMIN 사후 검토 목록(raw_view_reviews)에 남긴다
// 허용 기록은 같은 트랜잭션 — 기록이 실패하면 검토 행도 되돌리고 응답을 내보내지 않는다 (ADR-0082 4절)
@Service
@RequiredArgsConstructor
public class CaseContactService {
    private final SecurityStaffGuard staffGuard;
    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final RawViewReviewRepository rawViewReviewRepository;
    private final CustomerContactReader contactReader;
    private final StaffAccessRecorder accessRecorder;

    @Transactional
    public CaseContactResponse masked(Long caseId, Long paymentId) {
        Long staffId = staffGuard.requireSecurityStaffId();
        SecurityCase securityCase = requireScope(caseId, paymentId, staffId, StaffAccessType.CONTACT_MASKED_VIEW);
        Optional<MaskedContact> masked = contactReader.masked(paymentId);
        accessRecorder.recordAllowed(access(StaffAccessType.CONTACT_MASKED_VIEW, securityCase, paymentId)
                .build());
        return masked.map(contact -> CaseContactResponse.builder()
                        .paymentId(paymentId)
                        .available(true)
                        .ordererName(contact.ordererName())
                        .ordererPhone(contact.ordererPhone())
                        .pickupDiffers(contact.pickupDiffers())
                        .build())
                .orElseGet(() -> CaseContactResponse.builder()
                        .paymentId(paymentId)
                        .available(false)
                        .build());
    }

    // 이 결제 주문자하고만 비교한다. 결과(일치 · 불일치)는 기록 종류로 남기고 입력 번호는 남기지 않는다
    @Transactional
    public ContactMatchResponse match(Long caseId, Long paymentId, String phone) {
        Long staffId = staffGuard.requireSecurityStaffId();
        SecurityCase securityCase = requireScope(caseId, paymentId, staffId, StaffAccessType.CONTACT_MATCH);
        ContactMatchResult result;
        try {
            result = contactReader.matchOrdererPhone(paymentId, phone);
        } catch (IllegalArgumentException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_PHONE);
        }
        if (result == ContactMatchResult.NO_CONTACT) {
            throw new InvestigationException(InvestigationErrorCode.CONTACT_NOT_FOUND);
        }
        boolean matched = result == ContactMatchResult.MATCHED;
        accessRecorder.recordAllowed(access(
                        matched ? StaffAccessType.CONTACT_MATCH_HIT : StaffAccessType.CONTACT_MATCH_MISS,
                        securityCase,
                        paymentId)
                .fieldType(StaffAccessFieldType.ORDERER_PHONE)
                .build());
        return new ContactMatchResponse(matched);
    }

    @Transactional
    public RawViewResponse rawView(Long caseId, Long paymentId, RawViewRequest request) {
        Long staffId = staffGuard.requireSecurityStaffId();
        SecurityCase securityCase = requireScope(caseId, paymentId, staffId, StaffAccessType.CONTACT_RAW_VIEW);
        String phone = contactReader
                .ordererPhone(paymentId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CONTACT_NOT_FOUND));
        rawViewReviewRepository.save(RawViewReview.record(
                caseId,
                paymentId,
                staffId,
                request.disputeRef().strip(),
                request.merchantContactAttempt().strip(),
                request.reason().strip(),
                Instant.now()));
        accessRecorder.recordAllowed(access(StaffAccessType.CONTACT_RAW_VIEW, securityCase, paymentId)
                .fieldType(StaffAccessFieldType.ORDERER_PHONE)
                .build());
        return new RawViewResponse(phone);
    }

    // 거부는 호출 쪽 롤백 뒤에도 남는다 (StaffAccessRecorder.recordDenied — 독립 트랜잭션)
    private SecurityCase requireScope(Long caseId, Long paymentId, Long staffId, StaffAccessType type) {
        SecurityCase securityCase = securityCaseRepository
                .findById(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        if (securityCase.isClosed()) {
            deny(type, securityCase, paymentId, StaffAccessDenyReason.CASE_CLOSED);
            throw new InvestigationException(InvestigationErrorCode.CONTACT_CASE_CLOSED);
        }
        if (!staffId.equals(securityCase.getAssigneeStaffId())) {
            deny(type, securityCase, paymentId, StaffAccessDenyReason.NOT_ASSIGNEE);
            throw new InvestigationException(InvestigationErrorCode.CASE_ASSIGNEE_ONLY);
        }
        // 내 사건 번호에 사건 밖 결제 번호를 넣어 다른 고객 연락처를 여는 시도를 막는다
        boolean inCase = casePaymentRepository
                .findByPaymentId(paymentId)
                .map(link -> link.getSecurityCase().getId().equals(securityCase.getId()))
                .orElse(false);
        if (!inCase) {
            deny(type, securityCase, paymentId, StaffAccessDenyReason.PAYMENT_NOT_IN_CASE);
            throw new InvestigationException(InvestigationErrorCode.PAYMENT_NOT_IN_CASE);
        }
        return securityCase;
    }

    private void deny(StaffAccessType type, SecurityCase securityCase, Long paymentId, StaffAccessDenyReason reason) {
        accessRecorder.recordDenied(access(type, securityCase, paymentId).build(), reason);
    }

    private static StaffAccess.StaffAccessBuilder access(
            StaffAccessType type, SecurityCase securityCase, Long paymentId) {
        return StaffAccess.builder()
                .accessType(type)
                .caseId(securityCase.getId())
                .merchantId(securityCase.getMerchantId())
                .paymentId(paymentId);
    }
}
