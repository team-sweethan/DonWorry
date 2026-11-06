package com.donworry.investigation.service;

import com.donworry.identity.api.MerchantDirectoryEntry;
import com.donworry.identity.api.MerchantDirectoryReader;
import com.donworry.identity.api.SecurityStaffDirectory;
import com.donworry.identity.api.StaffSummary;
import com.donworry.investigation.dto.RawViewReviewRequest;
import com.donworry.investigation.dto.RawViewReviewResponse;
import com.donworry.investigation.entity.RawViewReview;
import com.donworry.investigation.entity.RawViewReviewStatus;
import com.donworry.investigation.entity.SecurityCase;
import com.donworry.investigation.repository.RawViewReviewRepository;
import com.donworry.investigation.repository.SecurityCaseRepository;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// ADMIN 원문 열람 사후 검토 (#619, ADR-0082) — 목록 · 대기 건수 · [적절] / [소명 요청]
// 목록에는 사건 · 결제 번호, 가맹점 · 직원 이름, 직원이 적은 근거만 — 원문 · 가린 값은 없다. 소명 답변 화면은 2차
@Service
@RequiredArgsConstructor
public class RawViewReviewService {
    private final RawViewReviewRepository reviews;
    private final SecurityCaseRepository securityCaseRepository;
    private final SecurityStaffDirectory staffDirectory;
    private final MerchantDirectoryReader merchantDirectory;
    private final SecurityStaffGuard guard;

    @Transactional(readOnly = true)
    public List<RawViewReviewResponse> list(String statusName) {
        guard.requireAdmin();
        List<RawViewReview> rows = reviews.findTop100ByStatusOrderByViewedAtDescIdDesc(parse(statusName));
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, SecurityCase> cases =
                securityCaseRepository
                        .findByIdIn(rows.stream()
                                .map(RawViewReview::getCaseId)
                                .distinct()
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(SecurityCase::getId, Function.identity()));
        Map<Long, String> staffNames = new HashMap<>();
        staffDirectory.securityStaff().forEach(entry -> staffNames.put(entry.userId(), entry.displayName()));
        Map<Long, String> merchantNames = merchantDirectory.findAll().stream()
                .collect(Collectors.toMap(
                        MerchantDirectoryEntry::merchantId, MerchantDirectoryEntry::displayName, (a, b) -> a));
        return rows.stream()
                .map(row -> {
                    Long merchantId = cases.get(row.getCaseId()).getMerchantId();
                    return RawViewReviewResponse.builder()
                            .reviewId(row.getId())
                            .caseId(row.getCaseId())
                            .paymentId(row.getPaymentId())
                            .merchantId(merchantId)
                            .merchantName(merchantNames.get(merchantId))
                            .staffId(row.getStaffId())
                            .staffName(staffNames.get(row.getStaffId()))
                            .disputeRef(row.getDisputeRef())
                            .merchantContactAttempt(row.getMerchantContactAttempt())
                            .reason(row.getReason())
                            .viewedAt(row.getViewedAt())
                            .status(row.getStatus().name())
                            .reviewedByName(row.getReviewedByAdminName())
                            .reviewedAt(row.getReviewedAt())
                            .reviewMemo(row.getReviewMemo())
                            .build();
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount() {
        guard.requireAdmin();
        return reviews.countByStatus(RawViewReviewStatus.PENDING);
    }

    @Transactional
    public void review(Long reviewId, RawViewReviewRequest request) {
        StaffSummary admin = guard.requireAdmin();
        RawViewReviewStatus result = parse(request.result());
        if (result == RawViewReviewStatus.PENDING) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_REVIEW_RESULT);
        }
        RawViewReview review = reviews.findByIdForUpdate(reviewId)
                .orElseThrow(() -> new InvestigationException(InvestigationErrorCode.RAW_VIEW_REVIEW_NOT_FOUND));
        if (!review.isPending()) {
            throw new InvestigationException(InvestigationErrorCode.RAW_VIEW_REVIEW_DONE);
        }
        String memo = request.memo() == null || request.memo().isBlank()
                ? null
                : request.memo().strip();
        review.review(result, admin.userId(), admin.displayName(), memo, Instant.now());
    }

    // controller가 entity enum을 모르게 문자열로 받는다 (ArchitectureTests)
    private static RawViewReviewStatus parse(String name) {
        try {
            return RawViewReviewStatus.valueOf(name);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvestigationException(InvestigationErrorCode.INVALID_REVIEW_RESULT);
        }
    }
}
