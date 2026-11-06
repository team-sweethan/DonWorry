package com.donworry.investigation.controller;

import com.donworry.investigation.dto.AssignedCaseResponse;
import com.donworry.investigation.dto.AssignmentApproveRequest;
import com.donworry.investigation.dto.AssignmentRejectRequest;
import com.donworry.investigation.dto.AssignmentRequestResponse;
import com.donworry.investigation.dto.CaseAssignmentOverviewResponse;
import com.donworry.investigation.dto.CaseHandOverRequest;
import com.donworry.investigation.dto.CaseReassignRequest;
import com.donworry.investigation.service.CaseAssignmentService;
import com.donworry.investigation.service.CaseReassignmentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자 사건 담당 API (#608) — 담당 현황 · 담당 변경 · 모두 넘기기 · 미배정 배정 · 변경 요청 처리
// 사건은 번호 · 가맹점 · 상태 · 생성 시각만 보여 준다. ADMIN은 FDS 사건 상세를 보지 않는다 (ADR-0065)
// 역할은 SecurityConfig가 막고, 서비스가 SecurityStaffGuard.requireAdmin으로 한 번 더 확인한다
@RestController
@RequestMapping("/api/admin/case-assignments")
@RequiredArgsConstructor
public class AdminCaseAssignmentController {

    private final CaseAssignmentService caseAssignmentService;
    private final CaseReassignmentService caseReassignmentService;

    @GetMapping
    public CaseAssignmentOverviewResponse overview() {
        return caseReassignmentService.overview();
    }

    @GetMapping("/staff/{staffId}/cases")
    public List<AssignedCaseResponse> casesOf(@PathVariable Long staffId) {
        return caseReassignmentService.casesOf(staffId);
    }

    @PostMapping("/cases/{caseId}/reassign")
    public void reassign(@PathVariable Long caseId, @Valid @RequestBody CaseReassignRequest body) {
        caseReassignmentService.reassign(caseId, body);
    }

    @PostMapping("/staff/{staffId}/handover")
    public Map<String, Integer> handOver(@PathVariable Long staffId, @Valid @RequestBody CaseHandOverRequest body) {
        return Map.of("moved", caseReassignmentService.handOverAll(staffId, body));
    }

    // 기능 도입 전에 만든 미배정 사건을 자동 배정 순서로 채운다
    @PostMapping("/backfill")
    public Map<String, Integer> backfill() {
        return Map.of("assigned", caseAssignmentService.backfill());
    }

    @GetMapping("/requests")
    public List<AssignmentRequestResponse> requests(@RequestParam(defaultValue = "PENDING") String status) {
        return caseReassignmentService.requests(status);
    }

    // 사이드바 배지 — 사용자 동작 때만 부른다 (Case 배지와 같은 이유)
    @GetMapping("/requests/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("count", caseReassignmentService.pendingCount());
    }

    @PostMapping("/requests/{requestId}/approve")
    public void approve(@PathVariable Long requestId, @Valid @RequestBody AssignmentApproveRequest body) {
        caseReassignmentService.approve(requestId, body);
    }

    @PostMapping("/requests/{requestId}/reject")
    public void reject(@PathVariable Long requestId, @Valid @RequestBody AssignmentRejectRequest body) {
        caseReassignmentService.reject(requestId, body);
    }
}
