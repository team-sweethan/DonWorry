package com.donworry.investigation.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.donworry.MariaDbTestContainerConfig;
import com.donworry.investigation.entity.ActionHandoff;
import com.donworry.investigation.entity.ActorType;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.CasePayment;
import com.donworry.investigation.entity.CaseType;
import com.donworry.investigation.entity.Decision;
import com.donworry.investigation.entity.HandoffStatus;
import com.donworry.investigation.entity.PaymentDecision;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.service.InvestigationRules;
import jakarta.persistence.EntityManager;
import java.lang.reflect.Method;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

// 실제 MariaDB에서 P3 Entity가 migration 컬럼 · 제약과 맞게 저장 · 조회되는지 확인한다
// 각 테스트는 트랜잭션 안에서 돌고 끝나면 롤백된다
@Tag("mariadb")
@SpringBootTest
@Import(MariaDbTestContainerConfig.class)
@Transactional
class InvestigationRepositoryTests {

    // DATETIME(6)은 마이크로초까지만 저장하므로 비교값도 맞춰 둔다
    static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);

    @Autowired
    SecurityCaseRepository securityCaseRepository;

    @Autowired
    CasePaymentRepository casePaymentRepository;

    @Autowired
    PaymentDecisionRepository paymentDecisionRepository;

    @Autowired
    ActionHandoffRepository actionHandoffRepository;

    @Autowired
    CaseHistoryRepository caseHistoryRepository;

    @Autowired
    EntityManager entityManager;

    // Case · 결제 · 판정 · 인계 · 이력을 저장하고 다시 읽으면 값이 그대로다
    @Test
    void entities_savedAndReloaded_keepValues() {
        SecurityCase securityCase = saveCase(1L, "customer-1", NOW);
        CasePayment casePayment = casePaymentRepository.save(CasePayment.link(securityCase, 1001L, NOW));
        PaymentDecision decision = paymentDecisionRepository.save(
                PaymentDecision.confirm(casePayment, 5L, null, Decision.REJECT, "수동 조사 근거", 1, NOW));
        ActionHandoff handoff = actionHandoffRepository.save(ActionHandoff.request(decision, NOW));
        caseHistoryRepository.save(CaseHistory.byStaff(
                securityCase,
                CaseHistoryEventType.DECISION_CONFIRMED,
                5L,
                Map.of("decisionId", decision.getId(), "decision", "REJECT"),
                NOW));
        entityManager.flush();
        entityManager.clear();

        ActionHandoff reloaded =
                actionHandoffRepository.findById(handoff.getId()).orElseThrow();
        assertThat(reloaded.getIdempotencyKey()).isEqualTo(handoff.getIdempotencyKey());
        assertThat(reloaded.getStatus()).isEqualTo(HandoffStatus.REQUESTED);
        assertThat(reloaded.getRequestedAt()).isEqualTo(NOW);
        assertThat(reloaded.getDecision().getReportId()).isNull();
        assertThat(reloaded.getDecision().getReason()).isEqualTo("수동 조사 근거");

        CaseHistory history = caseHistoryRepository
                .findBySecurityCaseIdOrderByCreatedAtAscIdAsc(securityCase.getId())
                .getFirst();
        assertThat(history.getActorType()).isEqualTo(ActorType.STAFF);
        assertThat(history.getEventData()).containsEntry("decision", "REJECT");
    }

    // 병합 후보는 같은 가맹점 · 고객이고 기한 안이며 판정이 없는 Case만이다
    @Test
    void findMergeCandidates_returnsOnlyOpenUndecidedCaseInWindow() {
        SecurityCase target = saveCase(1L, "customer-1", NOW.minusSeconds(10 * 60));
        saveCase(1L, "customer-1", NOW.minusSeconds(31 * 60));
        saveCase(1L, "customer-2", NOW.minusSeconds(60));
        saveCase(2L, "customer-1", NOW.minusSeconds(60));
        SecurityCase decided = saveCase(1L, "customer-1", NOW.minusSeconds(5 * 60));
        CasePayment decidedPayment = casePaymentRepository.save(CasePayment.link(decided, 2001L, NOW));
        paymentDecisionRepository.save(
                PaymentDecision.confirm(decidedPayment, 5L, 9L, Decision.ACCEPT, "정상 거래", 1, NOW));
        entityManager.flush();

        var candidates = securityCaseRepository.findMergeCandidates(
                1L,
                "customer-1",
                CaseType.PAYMENT_FRAUD,
                InvestigationRules.MERGEABLE_STATUSES,
                NOW.minus(InvestigationRules.MERGE_WINDOW));

        assertThat(candidates).extracting(SecurityCase::getId).containsExactly(target.getId());
    }

    // 같은 결제를 두 Case에 연결하면 DB가 막는다
    @Test
    void casePayment_samePaymentTwice_violatesUniqueConstraint() {
        SecurityCase first = saveCase(1L, "customer-1", NOW);
        SecurityCase second = saveCase(1L, "customer-1", NOW);
        casePaymentRepository.save(CasePayment.link(first, 3001L, NOW));
        entityManager.flush();

        assertThatThrownBy(() -> {
                    casePaymentRepository.save(CasePayment.link(second, 3001L, NOW));
                    entityManager.flush();
                })
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // 재시도 대상은 마지막 시도가 기준 시각보다 오래된 REQUESTED 인계다
    @Test
    void findByStatusAndLastAttemptedAtBefore_returnsStaleRequestedHandoff() {
        SecurityCase securityCase = saveCase(1L, "customer-1", NOW);
        ActionHandoff stale = saveHandoff(securityCase, 4001L, NOW.minusSeconds(600));
        saveHandoff(securityCase, 4002L, NOW);
        entityManager.flush();

        var retryTargets = actionHandoffRepository.findByStatusAndLastAttemptedAtBefore(
                HandoffStatus.REQUESTED, NOW.minusSeconds(300));

        assertThat(retryTargets).extracting(ActionHandoff::getId).containsExactly(stale.getId());
    }

    // 나노초가 있는 시각으로 만들어도 저장 전 값과 다시 읽은 값이 같다
    @Test
    void securityCase_createdWithNanos_keepsSameCreatedAtAfterReload() {
        Instant withNanos = Instant.parse("2026-10-22T05:00:00.123456789Z");
        SecurityCase saved = saveCase(1L, "customer-1", withNanos);
        entityManager.flush();
        entityManager.clear();

        SecurityCase reloaded = securityCaseRepository.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isEqualTo(saved.getCreatedAt());
    }

    // 잠금 조회도 같은 인계 행을 돌려준다 (SELECT ... FOR UPDATE가 MariaDB에서 실행된다)
    @Test
    void handoffLockQueries_returnSameRow() {
        SecurityCase securityCase = saveCase(1L, "customer-1", NOW);
        ActionHandoff handoff = saveHandoff(securityCase, 5001L, NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(actionHandoffRepository.findByIdForUpdate(handoff.getId()))
                .get()
                .extracting(ActionHandoff::getId)
                .isEqualTo(handoff.getId());
        assertThat(actionHandoffRepository.findByIdempotencyKey(handoff.getIdempotencyKey()))
                .get()
                .extracting(ActionHandoff::getId)
                .isEqualTo(handoff.getId());
        assertThat(securityCaseRepository.findByIdForUpdate(securityCase.getId()))
                .get()
                .extracting(SecurityCase::getId)
                .isEqualTo(securityCase.getId());
    }

    // 조사 기록 저장소는 물리 삭제 메서드를 노출하지 않는다 (AGENTS.md 4절)
    @Test
    void repositories_doNotExposeDelete() {
        var repositories = List.of(
                SecurityCaseRepository.class,
                CasePaymentRepository.class,
                PaymentDecisionRepository.class,
                ActionHandoffRepository.class,
                CaseHistoryRepository.class);

        assertThat(repositories)
                .flatExtracting(type -> Arrays.asList(type.getMethods()))
                .extracting(Method::getName)
                .noneMatch(name -> name.startsWith("delete"));
    }

    private SecurityCase saveCase(Long merchantId, String customerRef, Instant createdAt) {
        return securityCaseRepository.save(
                SecurityCase.open(merchantId, customerRef, CaseType.PAYMENT_FRAUD, createdAt));
    }

    private ActionHandoff saveHandoff(SecurityCase securityCase, Long paymentId, Instant requestedAt) {
        CasePayment casePayment = casePaymentRepository.save(CasePayment.link(securityCase, paymentId, NOW));
        PaymentDecision decision = paymentDecisionRepository.save(
                PaymentDecision.confirm(casePayment, 5L, 9L, Decision.ACCEPT, "정상 거래", 1, NOW));
        return actionHandoffRepository.save(ActionHandoff.request(decision, requestedAt));
    }
}
