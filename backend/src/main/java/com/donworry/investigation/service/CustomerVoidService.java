package com.donworry.investigation.service;

import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.CasePaymentRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import com.donworry.investigation.service.CustomerVoidReader.CustomerVoid;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 고객 HELD 승인 취소를 Case에 반영한다 (#382, docs/payment/382-held-cancellation.md 13절)
// 원본은 P5다. P3는 P5 공개 조회로 확인한 뒤 Case 이력(PAYMENT_CANCELED_BY_CUSTOMER)만 남기고 상태를 다시 계산한다
// 반영 경로는 두 가지이고 둘 다 cancellationId 기준으로 한 번만 기록한다
//   1. P5 성공 이벤트 수신(apply) — 결제가 아직 Case에 연결되지 않았으면 예외로 끝나 publication이 미완료로 남는다
//   2. 결제를 Case에 연결한 커밋 직후(reflectCase) — 취소가 연결보다 먼저 확정된 경우를 잡는다
// 취소가 연결 커밋 전에 확정되면 2가, 연결 커밋 뒤에 확정되면 1이 반영하므로 어느 순서로 와도 빠지지 않는다
@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerVoidService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CasePaymentRepository casePaymentRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseStatusService caseStatusService;
    private final CustomerVoidReader customerVoidReader;

    // 커밋 뒤 수신에서 부른다. 연결 행을 먼저 읽고 Case를 잠그므로, 잠금 전에 읽은 스냅숏이 고정되지 않게 READ_COMMITTED로 연다
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void apply(CustomerVoid event) {
        Objects.requireNonNull(event, "event");
        CasePayment casePayment = casePaymentRepository
                .findByPaymentId(event.paymentId())
                .orElseThrow(() ->
                        new IllegalStateException("고객 취소 결제가 아직 Case에 연결되지 않았습니다 paymentId=" + event.paymentId()));
        SecurityCase securityCase = securityCaseRepository
                .findByIdForUpdate(casePayment.getSecurityCase().getId())
                .orElseThrow();
        if (!securityCase.getMerchantId().equals(event.merchantId())) {
            throw new IllegalStateException("고객 취소의 가맹점이 Case와 다릅니다 paymentId=" + event.paymentId());
        }
        // 이벤트 값을 그대로 믿지 않고 P5 원본과 대조한다 — 다르면 덮어쓰지 않고 실패로 남긴다
        CustomerVoid source = customerVoidReader
                .find(event.merchantId(), event.paymentId())
                .orElseThrow(
                        () -> new IllegalStateException("P5에 고객 승인 취소 성공 원본이 없습니다 paymentId=" + event.paymentId()));
        if (!source.equals(event)) {
            throw new IllegalStateException("고객 취소 이벤트가 P5 원본과 다릅니다 paymentId=" + event.paymentId());
        }
        record(securityCase, casePayment, source, Instant.now());
    }

    // 결제 연결 커밋 뒤(CaseEvidenceChanged)에 부른다. 아직 반영하지 않은 결제만 P5에 묻는다
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void reflectCase(Long caseId) {
        SecurityCase securityCase =
                securityCaseRepository.findByIdForUpdate(caseId).orElseThrow();
        Map<Long, Long> reflected = caseStatusService.canceledCasePayments(caseId);
        Instant now = Instant.now();
        for (CasePayment casePayment : casePaymentRepository.findBySecurityCaseIdOrderByLinkedAtAsc(caseId)) {
            if (reflected.containsKey(casePayment.getId())) {
                continue;
            }
            customerVoidReader
                    .find(securityCase.getMerchantId(), casePayment.getPaymentId())
                    .ifPresent(source -> record(securityCase, casePayment, source, now));
        }
    }

    private void record(SecurityCase securityCase, CasePayment casePayment, CustomerVoid source, Instant now) {
        Long already =
                caseStatusService.canceledCasePayments(securityCase.getId()).get(casePayment.getId());
        if (already != null) {
            if (already.equals(source.cancellationId())) {
                log.info("이미 반영한 고객 취소 paymentId={} cancellationId={}", source.paymentId(), already);
                return;
            }
            // 한 결제에 고객 취소 성공은 하나뿐이다. 다른 ID가 오면 원본 불일치라 덮어쓰지 않는다
            throw new IllegalStateException("같은 결제에 다른 고객 취소가 왔습니다 paymentId=" + source.paymentId());
        }
        caseHistoryRepository.save(CaseHistory.bySystem(
                securityCase,
                CaseHistoryEventType.PAYMENT_CANCELED_BY_CUSTOMER,
                Map.of(
                        "paymentId", source.paymentId(),
                        "casePaymentId", casePayment.getId(),
                        "cancellationId", source.cancellationId(),
                        "completedAt", source.completedAt().toString()),
                now));
        caseStatusService.refresh(securityCase, now);
        log.info(
                "고객 승인 취소 반영 caseId={} paymentId={} cancellationId={}",
                securityCase.getId(),
                source.paymentId(),
                source.cancellationId());
    }
}
