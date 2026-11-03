package com.donworry.fds.service;

import com.donworry.fds.api.FdsRuleChangeRequestPage;
import com.donworry.fds.api.FdsRuleChangeRequestStatus;
import com.donworry.fds.api.FdsRulePolicySummary;
import com.donworry.fds.entity.FdsRuleChangeRequest;
import com.donworry.fds.repository.FdsRuleChangeRequestRepository;
import com.donworry.fds.repository.FdsRulePolicyRepository;
import com.donworry.identity.api.CurrentStaff;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FdsRuleManagementService {
    private final FdsRulePolicyRepository policies;
    private final FdsRuleChangeRequestRepository requests;
    private final CurrentStaff currentStaff;

    public List<FdsRulePolicySummary> policies() {
        return policies.findAll().stream()
                .map(policy -> new FdsRulePolicySummary(
                        policy.getRuleCode(),
                        policy.isActive(),
                        requests.existsByRuleCodeAndStatus(policy.getRuleCode(), FdsRuleChangeRequest.Status.PENDING)))
                .toList();
    }

    @Transactional
    public void requestDisable(String ruleCode, String reason) {
        var actor = requireRole("SECURITY_STAFF");
        var policy = policyForUpdate(ruleCode);
        if (!policy.isActive()) throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 꺼진 룰입니다.");
        if (requests.existsByRuleCodeAndStatus(ruleCode, FdsRuleChangeRequest.Status.PENDING)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 승인 대기 중인 요청이 있습니다.");
        }
        requests.save(FdsRuleChangeRequest.disable(
                ruleCode, actor.userId(), displayName(actor), normalizeReason(reason), Instant.now()));
    }

    @Transactional
    public void setActive(String ruleCode, boolean active) {
        var actor = requireCurrent();
        if (!"ADMIN".equals(actor.role()) && (!"SECURITY_STAFF".equals(actor.role()) || !active)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
        }
        var policy = policyForUpdate(ruleCode);
        if ("SECURITY_STAFF".equals(actor.role()) && policy.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 켜진 룰입니다.");
        }
        var changedAt = Instant.now();
        if (policy.isActive() != active) {
            policy.update(active, actor.userId(), changedAt);
        }
        if ("ADMIN".equals(actor.role())) {
            resolvePendingRequests(
                    ruleCode,
                    active ? FdsRuleChangeRequest.Status.REJECTED : FdsRuleChangeRequest.Status.APPROVED,
                    actor,
                    changedAt);
        }
    }

    public FdsRuleChangeRequestPage requests(FdsRuleChangeRequestStatus status, int page, int size) {
        requireRole("ADMIN");
        var result = requests.findByStatusOrderByRequestedAtDesc(
                FdsRuleChangeRequest.Status.valueOf(status.name()),
                PageRequest.of(page, size, Sort.by("requestedAt").descending()));
        return new FdsRuleChangeRequestPage(
                result.map(request -> new FdsRuleChangeRequestPage.Item(
                                request.getRuleChangeRequestId(),
                                request.getRuleCode(),
                                request.getRequestedByUserId(),
                                request.getRequestedByName(),
                                request.getReason(),
                                request.getRequestedAt(),
                                request.getStatus().name(),
                                request.getReviewedByUserId(),
                                request.getReviewedByName(),
                                request.getReviewedAt()))
                        .toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages());
    }

    @Transactional
    public void review(Long requestId, boolean approve) {
        var actor = requireRole("ADMIN");
        var ruleCode = requests.findRuleCodeById(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요청이 없습니다."));
        var policy = policyForUpdate(ruleCode);
        var request = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "요청이 없습니다."));
        if (request.getStatus() != FdsRuleChangeRequest.Status.PENDING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 요청입니다.");
        }
        var reviewedAt = Instant.now();
        request.review(
                approve ? FdsRuleChangeRequest.Status.APPROVED : FdsRuleChangeRequest.Status.REJECTED,
                actor.userId(),
                displayName(actor),
                reviewedAt);
        if (approve) {
            policy.update(false, actor.userId(), reviewedAt);
        }
    }

    private com.donworry.identity.api.StaffSummary requireRole(String role) {
        var actor = requireCurrent();
        if (!role.equals(actor.role())) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "권한이 없습니다.");
        return actor;
    }

    private com.donworry.fds.entity.FdsRulePolicy policyForUpdate(String ruleCode) {
        return policies.findByRuleCodeForUpdate(ruleCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알 수 없는 룰입니다."));
    }

    private com.donworry.identity.api.StaffSummary requireCurrent() {
        return currentStaff
                .findCurrent()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    private void resolvePendingRequests(
            String ruleCode,
            FdsRuleChangeRequest.Status resolution,
            com.donworry.identity.api.StaffSummary actor,
            Instant reviewedAt) {
        for (var request : requests.findByRuleCodeAndStatus(ruleCode, FdsRuleChangeRequest.Status.PENDING)) {
            request.review(resolution, actor.userId(), displayName(actor), reviewedAt);
        }
    }

    private String displayName(com.donworry.identity.api.StaffSummary staff) {
        return staff.displayName() == null || staff.displayName().isBlank()
                ? "사용자 #" + staff.userId()
                : staff.displayName();
    }

    private String normalizeReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "끄기 요청 사유를 입력해주세요.");
        }
        var normalized = reason.trim();
        if (normalized.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "끄기 요청 사유는 500자 이하여야 합니다.");
        }
        return normalized;
    }
}
