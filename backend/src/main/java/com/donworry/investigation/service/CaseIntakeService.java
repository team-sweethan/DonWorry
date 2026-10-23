package com.donworry.investigation.service;

import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.repository.CasePaymentRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;

// HELD 결제를 받아 Case를 만들거나 30분 안의 Case에 병합한다 (fds 정본 03 §3.4)
// P5 HELD 이벤트 리스너 · 데모 API가 이 메서드를 부른다
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseIntakeService {

    private static final int MAX_ATTEMPTS = 3;

    private final CaseLinkService caseLinkService;
    private final CasePaymentRepository casePaymentRepository;

    // 같은 결제가 다시 와도 이미 연결된 Case를 돌려준다 (AGENTS 4절 멱등)
    // 같은 고객 결제가 동시에 처음 오면 병합 후보 잠금(빈 범위 잠금)끼리 교착되거나 UNIQUE(payment_id)에 막힌다
    // 그 트랜잭션만 롤백하고 다시 시도한다 — 먼저 커밋된 쪽이 보이므로 같은 Case를 돌려받거나 그 Case에 병합된다
    public Long receive(HeldPayment heldPayment) {
        for (int attempt = 1; ; attempt++) {
            try {
                return caseLinkService.link(heldPayment);
            } catch (DataIntegrityViolationException | PessimisticLockingFailureException e) {
                Optional<Long> linked = casePaymentRepository
                        .findByPaymentId(heldPayment.paymentId())
                        .map(CasePayment::getSecurityCase)
                        .map(securityCase -> securityCase.getId());
                if (linked.isPresent()) {
                    log.info("동시에 들어온 같은 결제 — 먼저 연결된 Case 사용 paymentId={}", heldPayment.paymentId());
                    return linked.get();
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.info("Case 연결 경합 — 다시 시도 paymentId={} attempt={}", heldPayment.paymentId(), attempt);
            }
        }
    }
}
