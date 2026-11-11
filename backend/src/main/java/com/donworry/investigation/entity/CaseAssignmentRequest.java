package com.donworry.investigation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 담당 보안 직원의 담당 변경 요청 (#608). 사건당 대기 1건은 DB 생성 칼럼(pending_case_id) UNIQUE도 막는다
@Entity
@Table(name = "case_assignment_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CaseAssignmentRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "request_id")
    private Long id;

    private Long caseId;

    // P1 platform_users 논리 참조
    private Long requestedByStaffId;

    @Enumerated(EnumType.STRING)
    private AssignmentChangeReason reason;

    private String memo;

    @Enumerated(EnumType.STRING)
    private AssignmentRequestStatus status;

    private Instant requestedAt;

    private Long reviewedByAdminId;

    // 처리 시점의 ADMIN 표시 이름 — SecurityStaffDirectory에 ADMIN이 없어 함께 남긴다
    private String reviewedByAdminName;

    private Instant reviewedAt;

    private String reviewMemo;

    private Long newAssigneeStaffId;

    @Builder(access = AccessLevel.PRIVATE)
    private CaseAssignmentRequest(
            Long caseId, Long requestedByStaffId, AssignmentChangeReason reason, String memo, Instant requestedAt) {
        this.caseId = Objects.requireNonNull(caseId, "caseId");
        this.requestedByStaffId = Objects.requireNonNull(requestedByStaffId, "requestedByStaffId");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.memo = memo;
        this.requestedAt = Objects.requireNonNull(requestedAt, "requestedAt").truncatedTo(ChronoUnit.MICROS);
        this.status = AssignmentRequestStatus.PENDING;
    }

    public static CaseAssignmentRequest open(
            Long caseId, Long staffId, AssignmentChangeReason reason, String memo, Instant now) {
        return CaseAssignmentRequest.builder()
                .caseId(caseId)
                .requestedByStaffId(staffId)
                .reason(reason)
                .memo(memo)
                .requestedAt(now)
                .build();
    }

    public void approve(Long adminId, String adminName, Long newAssigneeStaffId, String reviewMemo, Instant now) {
        review(adminId, adminName, reviewMemo, now);
        this.status = AssignmentRequestStatus.APPROVED;
        this.newAssigneeStaffId = Objects.requireNonNull(newAssigneeStaffId, "newAssigneeStaffId");
    }

    public void reject(Long adminId, String adminName, String reviewMemo, Instant now) {
        review(adminId, adminName, Objects.requireNonNull(reviewMemo, "reviewMemo"), now);
        this.status = AssignmentRequestStatus.REJECTED;
    }

    public boolean isPending() {
        return status == AssignmentRequestStatus.PENDING;
    }

    private void review(Long adminId, String adminName, String reviewMemo, Instant now) {
        if (!isPending()) {
            throw new IllegalStateException("대기 중인 요청만 처리합니다");
        }
        this.reviewedByAdminId = Objects.requireNonNull(adminId, "adminId");
        this.reviewedByAdminName = adminName;
        this.reviewMemo = reviewMemo;
        this.reviewedAt = Objects.requireNonNull(now, "now").truncatedTo(ChronoUnit.MICROS);
    }
}
