package com.donworry.investigation.service;

import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.SecurityStaffEntry;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.AssignedCaseResponse;
import com.donworry.investigation.dto.AssignmentApproveRequest;
import com.donworry.investigation.dto.AssignmentChangeSubmitRequest;
import com.donworry.investigation.dto.AssignmentRejectRequest;
import com.donworry.investigation.dto.AssignmentRequestResponse;
import com.donworry.investigation.dto.CaseAssignmentOverviewResponse;
import com.donworry.investigation.dto.CaseAssignmentOverviewResponse.StaffLoadResponse;
import com.donworry.investigation.dto.CaseHandOverRequest;
import com.donworry.investigation.dto.CaseReassignRequest;
import com.donworry.investigation.entity.AssignmentChangeReason;
import com.donworry.investigation.entity.AssignmentRequestStatus;
import com.donworry.investigation.entity.CaseAssignmentRequest;
import com.donworry.investigation.entity.CaseHistory;
import com.donworry.investigation.entity.CaseHistoryEventType;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.CaseAssignmentRequestRepository;
import com.donworry.investigation.repository.CaseHistoryRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// FDS 사건 담당 변경 (#608) — 담당 직원의 변경 요청, ADMIN 담당 변경 · 모두 넘기기 · 요청 승인/거절 · 현황 조회
// 잠금: ADMIN 변경은 모두 배정 잠금 행을 첫 문장에서 잡는다. 그래서 요청 행은 배정 잠금을 잡은 트랜잭션만 잠그고,
// 승인 · 거절은 요청 행 → 사건 행, 변경 · 넘기기는 사건 행 → 요청 행 순서여도 서로 엇갈리지 않는다
// 직원 요청 생성 · 판정은 배정 잠금 없이 사건 행 잠금을 첫 문장에서 잡는다 — 둘이 끼어들지 못한다
@Service
@RequiredArgsConstructor
public class CaseReassignmentService {

    private final SecurityCaseRepository securityCaseRepository;
    private final CaseAssignmentRequestRepository requestRepository;
    private final CaseHistoryRepository caseHistoryRepository;
    private final CaseAssignmentService caseAssignmentService;
    private final SecurityStaffDirectory staffDirectory;
    private final MerchantDirectoryReader merchantDirectory;
    private final SecurityStaffGuard guard;

    // 담당 직원 본인 · 열린 사건만. 대기 중에는 그 사건의 판정만 막힌다 (CaseDecisionService)
    @Transactional
    public void request(Long caseId, AssignmentChangeSubmitRequest body) {
        Long staffId = guard.requireSecurityStaffId();
        String memo = memo(body.reason(), body.memo());
        SecurityCase securityCase = openCaseForUpdate(caseId);
        if (!staffId.equals(securityCase.getAssigneeStaffId())) {
            throw new InvestigationException(InvestigationErrorCode.NOT_CASE_ASSIGNEE);
        }
        if (requestRepository.existsByCaseIdAndStatus(caseId, AssignmentRequestStatus.PENDING)) {
            throw new InvestigationException(InvestigationErrorCode.ASSIGNMENT_REQUEST_PENDING);
        }
        Instant now = Instant.now();
        CaseAssignmentRequest saved =
                requestRepository.save(CaseAssignmentRequest.open(caseId, staffId, body.reason(), memo, now));
        caseHistoryRepository.save(CaseHistory.byStaff(
                securityCase,
                CaseHistoryEventType.CASE_ASSIGNMENT_REQUESTED,
                staffId,
                Map.of("requestId", saved.getId(), "reason", body.reason().name()),
                now));
    }

    // ADMIN 담당 변경. 그 사건에 대기 요청이 있으면 이 변경으로 승인 처리한다 — 대기가 남으면 판정이 계속 막힌다
    @Transactional
    public void reassign(Long caseId, CaseReassignRequest body) {
        StaffSummary admin = guard.requireAdmin();
        String memo = memo(body.reason(), body.memo());
        caseAssignmentService.lock();
        SecurityCase securityCase = openCaseForUpdate(caseId);
        Instant now = Instant.now();
        SecurityStaffEntry next = activeStaff(body.newStaffId());
        moveTo(securityCase, next, "ADMIN_REASSIGN", body.reason(), memo, admin, null, now);
        approvePendingOf(securityCase, next.userId(), admin, now);
    }

    // ADMIN [모두 다른 직원에게 넘기기] — 그 직원의 미종결 사건을 자동 배정 순서로 나머지 직원에게 나눈다. 넘긴 건수를 돌려준다
    // 넘겨받을 직원이 없으면 한 건도 옮기지 않는다 (전체 롤백)
    @Transactional
    public int handOverAll(Long fromStaffId, CaseHandOverRequest body) {
        StaffSummary admin = guard.requireAdmin();
        String memo = memo(body.reason(), body.memo());
        caseAssignmentService.lock();
        List<SecurityCase> cases = securityCaseRepository.findOpenByAssigneeForUpdate(fromStaffId);
        Map<Long, Long> counts = caseAssignmentService.openCounts();
        Map<Long, SecurityStaffEntry> staff = staffById();
        Instant now = Instant.now();
        for (SecurityCase securityCase : cases) {
            CaseAssignmentRules.Choice choice = CaseAssignmentRules.choose(staff.values(), counts, Set.of(fromStaffId))
                    .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.NO_AVAILABLE_STAFF));
            moveTo(securityCase, staff.get(choice.staffId()), "HANDOVER", body.reason(), memo, admin, null, now);
            counts.merge(choice.staffId(), 1L, Long::sum);
            approvePendingOf(securityCase, choice.staffId(), admin, now);
        }
        return cases.size();
    }

    @Transactional
    public void approve(Long requestId, AssignmentApproveRequest body) {
        StaffSummary admin = guard.requireAdmin();
        caseAssignmentService.lock();
        CaseAssignmentRequest request = pendingForUpdate(requestId);
        SecurityCase securityCase = openCaseForUpdate(request.getCaseId());
        Instant now = Instant.now();
        SecurityStaffEntry next = activeStaff(body.newStaffId());
        moveTo(securityCase, next, "REQUEST_APPROVED", request.getReason(), null, admin, request.getId(), now);
        request.approve(admin.userId(), admin.displayName(), next.userId(), blankToNull(body.memo()), now);
    }

    // 거절하면 판정이 다시 열린다. 사건이 그사이 종료됐어도 대기 요청을 정리할 수 있게 종료 여부는 보지 않는다
    @Transactional
    public void reject(Long requestId, AssignmentRejectRequest body) {
        StaffSummary admin = guard.requireAdmin();
        caseAssignmentService.lock();
        CaseAssignmentRequest request = pendingForUpdate(requestId);
        SecurityCase securityCase = securityCaseRepository
                .findByIdForUpdate(request.getCaseId())
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        Instant now = Instant.now();
        String reviewMemo = body.memo().strip();
        request.reject(admin.userId(), admin.displayName(), reviewMemo, now);
        caseHistoryRepository.save(CaseHistory.byStaff(
                securityCase,
                CaseHistoryEventType.CASE_ASSIGNMENT_REQUEST_REJECTED,
                admin.userId(),
                Map.of("requestId", requestId, "actorRole", "ADMIN", "memo", reviewMemo),
                now));
    }

    @Transactional(readOnly = true)
    public CaseAssignmentOverviewResponse overview() {
        guard.requireAdmin();
        Map<Long, Long> counts = caseAssignmentService.openCounts();
        List<SecurityStaffEntry> staff = staffDirectory.securityStaff();
        List<StaffLoadResponse> rows = new ArrayList<>();
        List<CaseAssignmentRules.Choice> ranked = CaseAssignmentRules.rank(staff, counts, Set.of());
        for (int i = 0; i < ranked.size(); i++) {
            CaseAssignmentRules.Choice choice = ranked.get(i);
            rows.add(StaffLoadResponse.builder()
                    .staffId(choice.staffId())
                    .displayName(choice.displayName())
                    .active(true)
                    .openCount(choice.openCountBefore())
                    .assignOrder(i + 1)
                    .build());
        }
        staff.stream()
                .filter(entry -> !entry.active())
                .forEach(entry -> rows.add(StaffLoadResponse.builder()
                        .staffId(entry.userId())
                        .displayName(entry.displayName())
                        .active(false)
                        .openCount(counts.getOrDefault(entry.userId(), 0L))
                        .build()));
        return new CaseAssignmentOverviewResponse(
                rows,
                securityCaseRepository.countUnassignedOpen(),
                requestRepository.countByStatus(AssignmentRequestStatus.PENDING));
    }

    @Transactional(readOnly = true)
    public List<AssignedCaseResponse> casesOf(Long staffId) {
        guard.requireAdmin();
        List<SecurityCase> cases = securityCaseRepository.findOpenByAssignee(staffId);
        Set<Long> pending =
                pendingCaseIds(cases.stream().map(SecurityCase::getId).toList());
        Map<Long, String> merchants = merchantNames();
        return cases.stream()
                .map(securityCase -> AssignedCaseResponse.builder()
                        .caseId(securityCase.getId())
                        .merchantId(securityCase.getMerchantId())
                        .merchantName(merchants.get(securityCase.getMerchantId()))
                        .status(securityCase.getStatus().name())
                        .createdAt(securityCase.getCreatedAt())
                        .assignmentRequestPending(pending.contains(securityCase.getId()))
                        .build())
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AssignmentRequestResponse> requests(String statusName) {
        guard.requireAdmin();
        AssignmentRequestStatus status = parseStatus(statusName);
        List<CaseAssignmentRequest> requests = requestRepository.findTop100ByStatusOrderByRequestedAtDescIdDesc(status);
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, SecurityCase> cases =
                securityCaseRepository
                        .findByIdIn(requests.stream()
                                .map(CaseAssignmentRequest::getCaseId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(SecurityCase::getId, Function.identity()));
        Map<Long, String> names = staffNames();
        Map<Long, String> merchants = merchantNames();
        return requests.stream()
                .map(request -> {
                    SecurityCase securityCase = cases.get(request.getCaseId());
                    return AssignmentRequestResponse.builder()
                            .requestId(request.getId())
                            .caseId(request.getCaseId())
                            .merchantId(securityCase.getMerchantId())
                            .merchantName(merchants.get(securityCase.getMerchantId()))
                            .caseStatus(securityCase.getStatus().name())
                            .currentAssigneeStaffId(securityCase.getAssigneeStaffId())
                            .currentAssigneeName(names.get(securityCase.getAssigneeStaffId()))
                            .requestedByStaffId(request.getRequestedByStaffId())
                            .requestedByName(names.get(request.getRequestedByStaffId()))
                            .reason(request.getReason().name())
                            .memo(request.getMemo())
                            .status(request.getStatus().name())
                            .requestedAt(request.getRequestedAt())
                            .reviewedByName(request.getReviewedByAdminName())
                            .reviewedAt(request.getReviewedAt())
                            .reviewMemo(request.getReviewMemo())
                            .newAssigneeStaffId(request.getNewAssigneeStaffId())
                            .newAssigneeName(names.get(request.getNewAssigneeStaffId()))
                            .build();
                })
                .toList();
    }

    // 사이드바 배지 — 사용자 동작 때만 부른다 (Case 배지와 같은 이유)
    @Transactional(readOnly = true)
    public long pendingCount() {
        guard.requireAdmin();
        return requestRepository.countByStatus(AssignmentRequestStatus.PENDING);
    }

    private void moveTo(
            SecurityCase securityCase,
            SecurityStaffEntry next,
            String kind,
            AssignmentChangeReason reason,
            String memo,
            StaffSummary admin,
            Long requestId,
            Instant now) {
        Long previous = securityCase.getAssigneeStaffId();
        if (next.userId().equals(previous)) {
            throw new InvestigationException(InvestigationErrorCode.SAME_ASSIGNEE);
        }
        securityCase.assignTo(next.userId(), now);
        // 이전 담당 · 메모 · 요청 번호는 없을 수 있다 — CaseHistory는 null 값을 받지 않아 있는 것만 넣는다
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("assigneeStaffId", next.userId());
        data.put("assigneeName", next.displayName());
        data.put("reason", kind);
        data.put("changeReason", reason.name());
        data.put("actorRole", "ADMIN");
        if (previous != null) {
            data.put("previousStaffId", previous);
        }
        if (memo != null) {
            data.put("memo", memo);
        }
        if (requestId != null) {
            data.put("requestId", requestId);
        }
        caseHistoryRepository.save(
                CaseHistory.byStaff(securityCase, CaseHistoryEventType.CASE_ASSIGNED, admin.userId(), data, now));
    }

    // 사건 행을 잠근 뒤에 부른다 — 요청 생성도 사건 행 잠금을 먼저 잡으므로 그사이 새 대기 요청이 생기지 않는다
    private void approvePendingOf(SecurityCase securityCase, Long newStaffId, StaffSummary admin, Instant now) {
        requestRepository
                .findByCaseIdAndStatus(securityCase.getId(), AssignmentRequestStatus.PENDING)
                .flatMap(found -> requestRepository.findByIdForUpdate(found.getId()))
                .filter(CaseAssignmentRequest::isPending)
                .ifPresent(request -> request.approve(admin.userId(), admin.displayName(), newStaffId, null, now));
    }

    private SecurityStaffEntry activeStaff(Long staffId) {
        SecurityStaffEntry entry = staffById().get(staffId);
        if (entry == null || !entry.active()) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_ASSIGNEE);
        }
        return entry;
    }

    private SecurityCase openCaseForUpdate(Long caseId) {
        SecurityCase securityCase = securityCaseRepository
                .findByIdForUpdate(caseId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.CASE_NOT_FOUND));
        if (securityCase.isClosed()) {
            throw new InvestigationException(InvestigationErrorCode.CASE_CLOSED);
        }
        return securityCase;
    }

    private CaseAssignmentRequest pendingForUpdate(Long requestId) {
        CaseAssignmentRequest request = requestRepository
                .findByIdForUpdate(requestId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.ASSIGNMENT_REQUEST_NOT_FOUND));
        if (!request.isPending()) {
            throw new InvestigationException(InvestigationErrorCode.ASSIGNMENT_REQUEST_NOT_PENDING);
        }
        return request;
    }

    private Map<Long, SecurityStaffEntry> staffById() {
        Map<Long, SecurityStaffEntry> staff = new LinkedHashMap<>();
        staffDirectory.securityStaff().forEach(entry -> staff.put(entry.userId(), entry));
        return staff;
    }

    private Map<Long, String> staffNames() {
        Map<Long, String> names = new HashMap<>();
        staffDirectory.securityStaff().forEach(entry -> names.put(entry.userId(), entry.displayName()));
        return names;
    }

    private Map<Long, String> merchantNames() {
        return merchantDirectory.findAll().stream()
                .collect(Collectors.toMap(
                        MerchantDirectoryEntry::merchantId, MerchantDirectoryEntry::displayName, (a, b) -> a));
    }

    private Set<Long> pendingCaseIds(List<Long> caseIds) {
        if (caseIds.isEmpty()) {
            return Set.of();
        }
        return requestRepository.findByCaseIdInAndStatus(caseIds, AssignmentRequestStatus.PENDING).stream()
                .map(CaseAssignmentRequest::getCaseId)
                .collect(Collectors.toSet());
    }

    // controller가 entity enum을 모르게 문자열로 받는다. 모르는 값은 400
    private static AssignmentRequestStatus parseStatus(String name) {
        try {
            return AssignmentRequestStatus.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_REQUEST_STATUS);
        }
    }

    private static String memo(AssignmentChangeReason reason, String memo) {
        String clean = blankToNull(memo);
        if (reason == AssignmentChangeReason.OTHER && clean == null) {
            throw new InvestigationException(InvestigationErrorCode.ASSIGNMENT_MEMO_REQUIRED);
        }
        return clean;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
