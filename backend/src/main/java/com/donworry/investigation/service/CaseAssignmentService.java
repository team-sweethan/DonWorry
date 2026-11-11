package com.donworry.investigation.service;

import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.InsiderCaseRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// FDS 사건 담당 자동 배정 (#608). 배정 잠금 행으로 배정을 한 줄로 세운다
// 열린 내부자 사건이 있는 직원은 후보에서 뺀다 (#670) — 사건 열기도 같은 잠금 행을 잡아, 열리는 순간 배정되는 틈이 없다
@Slf4j
@Service
@RequiredArgsConstructor
public class CaseAssignmentService {

    static final String LOCK_NAME = "FDS_CASE";

    private final SecurityCaseRepository securityCaseRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final InsiderCaseRepository insiderCases;
    private final SecurityStaffDirectory staffDirectory;
    private final SecurityStaffGuard guard;

    // 배정 · 담당 변경 트랜잭션의 첫 DB 문장으로 부른다 — 먼저 일반 조회가 돌면 스냅숏이 잠금 전에 고정돼
    // 앞 트랜잭션이 막 배정한 사건을 세지 못한다 (SecurityCaseRepository.lockAssignment)
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (securityCaseRepository.lockAssignment(LOCK_NAME) == null) {
            throw new IllegalStateException("배정 잠금 행이 없습니다 (case_assignment_locks)");
        }
    }

    // 새 사건 생성 트랜잭션 안에서, lock() 뒤에 부른다. 직원이 없으면 미배정으로 두고 사건 생성은 계속한다 —
    // 배정 실패로 HELD 결제가 사건 없이 남으면 아무도 판정할 수 없다
    @Transactional(propagation = Propagation.MANDATORY)
    public void assignNew(SecurityCase securityCase, Instant now) {
        assign(securityCase, openCounts(), staffUnderInsiderCase(), null, "AUTO_LEAST_OPEN", now);
    }

    // 기능 도입 전에 만든 미배정 미종결 사건을 오래된 순서로 같은 규칙에 따라 배정한다 (ADMIN). 배정한 건수를 돌려준다
    @Transactional
    public int backfill() {
        StaffSummary admin = guard.requireAdmin();
        lock();
        List<SecurityCase> unassigned = securityCaseRepository.findUnassignedOpenForUpdate();
        Map<Long, Long> counts = openCounts();
        Set<Long> excluded = staffUnderInsiderCase();
        Instant now = Instant.now();
        int assigned = 0;
        for (SecurityCase securityCase : unassigned) {
            if (!assign(securityCase, counts, excluded, admin.userId(), "BACKFILL", now)) {
                break;
            }
            assigned++;
        }
        return assigned;
    }

    Map<Long, Long> openCounts() {
        Map<Long, Long> counts = new HashMap<>();
        securityCaseRepository.countOpenByAssignee().forEach(row -> counts.put(row.getStaffId(), row.getOpenCount()));
        return counts;
    }

    // 배정 잠금을 잡은 뒤에 부른다 — 그래야 그사이 열린 내부자 사건까지 본다
    Set<Long> staffUnderInsiderCase() {
        return insiderCases.findOpenStaffIds();
    }

    private boolean assign(
            SecurityCase securityCase,
            Map<Long, Long> counts,
            Set<Long> excluded,
            Long adminId,
            String kind,
            Instant now) {
        Optional<CaseAssignmentRules.Choice> choice =
                CaseAssignmentRules.choose(staffDirectory.securityStaff(), counts, excluded);
        if (choice.isEmpty()) {
            log.warn("배정할 보안 직원이 없어 미배정으로 둡니다 caseId={}", securityCase.getId());
            return false;
        }
        CaseAssignmentRules.Choice chosen = choice.get();
        securityCase.assignTo(chosen.staffId(), now);
        counts.merge(chosen.staffId(), 1L, Long::sum);
        Map<String, Object> data = Map.of(
                "assigneeStaffId", chosen.staffId(),
                "assigneeName", chosen.displayName(),
                "openCountBefore", chosen.openCountBefore(),
                "reason", kind);
        caseHistoryRepository.save(
                adminId == null
                        ? CaseHistory.bySystem(securityCase, CaseHistoryEventType.CASE_ASSIGNED, data, now)
                        : CaseHistory.byStaff(securityCase, CaseHistoryEventType.CASE_ASSIGNED, adminId, data, now));
        log.info("사건 담당 배정 caseId={} staffId={} reason={}", securityCase.getId(), chosen.staffId(), kind);
        return true;
    }
}
